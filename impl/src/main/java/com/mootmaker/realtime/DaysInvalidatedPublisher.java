package com.mootmaker.realtime;

import module java.base;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.http.SdkHttpRequest;
import software.amazon.awssdk.http.auth.aws.signer.AwsV4HttpSigner;
import software.amazon.awssdk.http.auth.spi.signer.SignedRequest;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.identity.spi.AwsCredentialsIdentity;
import software.amazon.awssdk.regions.Region;

/**
 * Broadcasts "these dates changed" to every connected client, by calling the API's own {@code
 * publishDaysInvalidated} mutation over IAM-signed HTTP.
 *
 * <p><b>Why a mutation and not a direct push.</b> AppSync has no server-side "publish" API. A
 * subscription is driven by a mutation: {@code @aws_subscribe} pushes the MUTATION'S return value
 * to subscribers. So the only way to broadcast is to call a mutation - and the one we call is a
 * publish-only field on a NONE data source that exists for no other purpose.
 *
 * <p><b>Why not subscribe to {@code createMeeting} directly</b>, which would need none of this.
 * Verified on a throwaway API rather than assumed: a rejected {@code createMeeting} returns
 * <em>successfully</em>, carrying a typed {@code errors} array, and AppSync broadcasts it exactly
 * like a success - so every client would be woken by bookings that never happened. Filtering it out
 * afterwards is possible but treacherous (see the design: two unsupported filter combinations
 * silently matched in opposite directions, neither raising an error). A dedicated field that is
 * only ever called after a write has already succeeded has nothing to filter.
 *
 * <p><b>Failure is swallowed, deliberately.</b> A broadcast is an optimisation: it saves other
 * clients a refetch they would otherwise make on their next navigation or foreground. The write
 * itself has already committed and been reported to the caller by the time this runs. Letting a
 * failed broadcast fail the mutation would turn a missed optimisation into a lost booking, and
 * would do it at the worst possible moment - when AppSync is already unhealthy. It is logged at
 * WARN so the failure is visible without being fatal.
 */
public final class DaysInvalidatedPublisher implements DayBroadcaster {

  private static final Logger LOG = LoggerFactory.getLogger(DaysInvalidatedPublisher.class);

  private static final String MUTATION =
      "mutation P($dates: [String!]!) { publishDaysInvalidated(dates: $dates) { dates } }";

  /**
   * Short on purpose. This runs AFTER the write has committed, inside the caller's request, so
   * every millisecond spent here is latency the user pays for an update meant for other people. A
   * broadcast that has not completed in two seconds is worth abandoning.
   */
  private static final Duration TIMEOUT = Duration.ofSeconds(2);

  private final SdkHttpClient http;
  private final AwsCredentialsProvider credentials;
  private final URI endpoint;
  private final Region region;

  public DaysInvalidatedPublisher(
      final SdkHttpClient http,
      final AwsCredentialsProvider credentials,
      final URI endpoint,
      final Region region) {
    this.http = http;
    this.credentials = credentials;
    this.endpoint = endpoint;
    this.region = region;
  }

  /**
   * Reads {@code GRAPHQL_ENDPOINT} and {@code AWS_REGION}, both set by Lambda/Terraform. Returns
   * {@link DayBroadcaster#NONE} when the endpoint is absent rather than throwing: the same jar
   * backs database-reset and database-repair, which have no reason to broadcast and no grant to do
   * so.
   */
  public static DayBroadcaster fromEnvironment() {
    final String endpoint = System.getenv("GRAPHQL_ENDPOINT");
    if (endpoint == null || endpoint.isBlank()) {
      LOG.info("GRAPHQL_ENDPOINT is not set; day invalidations will not be broadcast");
      return DayBroadcaster.NONE;
    }
    return new DaysInvalidatedPublisher(
        UrlConnectionHttpClient.builder().socketTimeout(TIMEOUT).connectionTimeout(TIMEOUT).build(),
        DefaultCredentialsProvider.create(),
        URI.create(endpoint),
        Region.of(System.getenv("AWS_REGION")));
  }

  /** Broadcasts the given dates. Never throws. */
  @Override
  public void publish(final Collection<String> dates) {
    if (dates == null || dates.isEmpty()) {
      return;
    }
    try {
      send(body(dates));
    } catch (final RuntimeException | IOException e) {
      // See the class javadoc: the write has already committed, so this must not propagate.
      LOG.warn(
          "Failed to broadcast day invalidation for {} - clients will refetch on their own",
          dates,
          e);
    }
  }

  private void send(final String body) throws IOException {
    final SdkHttpRequest unsigned =
        SdkHttpRequest.builder()
            .method(SdkHttpMethod.POST)
            .uri(endpoint)
            .putHeader("Content-Type", "application/json")
            .build();

    final SignedRequest signed =
        AwsV4HttpSigner.create()
            .sign(
                r ->
                    r.identity((AwsCredentialsIdentity) credentials.resolveCredentials())
                        .request(unsigned)
                        .payload(
                            () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)))
                        .putProperty(AwsV4HttpSigner.SERVICE_SIGNING_NAME, "appsync")
                        .putProperty(AwsV4HttpSigner.REGION_NAME, region.id()));

    final HttpExecuteResponse response =
        http.prepareRequest(
                HttpExecuteRequest.builder()
                    .request(signed.request())
                    .contentStreamProvider(signed.payload().orElse(null))
                    .build())
            .call();

    final int status = response.httpResponse().statusCode();
    if (status != 200) {
      LOG.warn("Day invalidation broadcast returned HTTP {}", status);
      return;
    }
    // A 200 is not success. AppSync reports authorization failures - including the silent
    // "Not Authorized to access dates on type Invalidation" that a missing type-level directive
    // produces - inside a 200 response body. Without this check that failure mode is invisible
    // on both sides: subscribers receive nothing and the publisher logs nothing.
    final String payload =
        response
            .responseBody()
            .map(
                s -> {
                  try (var in = s) {
                    return new String(in.readAllBytes(), StandardCharsets.UTF_8);
                  } catch (final IOException e) {
                    return "";
                  }
                })
            .orElse("");
    if (payload.contains("\"errors\"")) {
      LOG.warn("Day invalidation broadcast was rejected by AppSync: {}", payload);
    }
  }

  /** Hand-built rather than via a JSON library: two fields, one of them a list of plain dates. */
  private static String body(final Collection<String> dates) {
    final String quoted = dates.stream().map(d -> '"' + d + '"').collect(Collectors.joining(","));
    return "{\"query\":\""
        + MUTATION.replace("\"", "\\\"")
        + "\",\"variables\":{\"dates\":["
        + quoted
        + "]}}";
  }
}
