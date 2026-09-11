package com.mootmaker.realtime;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.http.ExecutableHttpRequest;
import software.amazon.awssdk.http.HttpExecuteRequest;
import software.amazon.awssdk.http.HttpExecuteResponse;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.regions.Region;

/**
 * The publisher's contract is mostly about what it does NOT do: it must never throw, because it
 * runs after the write has already committed and been reported to the caller. A broadcast that
 * fails is a missed optimisation; a broadcast that throws would be a lost booking.
 */
class DaysInvalidatedPublisherTest {

  /** Captures the signed request, and returns whatever the test wants AppSync to have said. */
  private static final class FakeHttpClient implements SdkHttpClient {

    private final int status;
    private final String responseBody;
    private final RuntimeException failure;
    final List<String> bodies = new ArrayList<>();
    final List<HttpExecuteRequest> requests = new ArrayList<>();

    FakeHttpClient(final int status, final String responseBody, final RuntimeException failure) {
      this.status = status;
      this.responseBody = responseBody;
      this.failure = failure;
    }

    @Override
    public ExecutableHttpRequest prepareRequest(final HttpExecuteRequest request) {
      return new ExecutableHttpRequest() {
        @Override
        public HttpExecuteResponse call() throws IOException {
          requests.add(request);
          request
              .contentStreamProvider()
              .ifPresent(
                  p -> {
                    try {
                      bodies.add(new String(p.newStream().readAllBytes(), StandardCharsets.UTF_8));
                    } catch (final IOException e) {
                      throw new UncheckedIOException(e);
                    }
                  });
          if (failure != null) {
            throw failure;
          }
          return HttpExecuteResponse.builder()
              .response(SdkHttpResponse.builder().statusCode(status).build())
              .responseBody(
                  AbortableInputStream.create(
                      new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8))))
              .build();
        }

        @Override
        public void abort() {}
      };
    }

    @Override
    public void close() {}
  }

  private static DaysInvalidatedPublisher publisherUsing(final FakeHttpClient http) {
    return new DaysInvalidatedPublisher(
        http,
        StaticCredentialsProvider.create(AwsBasicCredentials.create("ak", "sk")),
        URI.create("https://example.appsync-api.us-east-1.amazonaws.com/graphql"),
        Region.US_EAST_1);
  }

  @Test
  @DisplayName("sends the dates as GraphQL variables")
  void sendsTheDates() {
    final FakeHttpClient http = new FakeHttpClient(200, "{\"data\":{}}", null);

    publisherUsing(http).publish(List.of("2026-09-14", "2026-09-15"));

    assertEquals(1, http.bodies.size(), "exactly one call per publish");
    assertTrue(http.bodies.getFirst().contains("\"2026-09-14\""), http.bodies.getFirst());
    assertTrue(http.bodies.getFirst().contains("\"2026-09-15\""), http.bodies.getFirst());
    assertTrue(http.bodies.getFirst().contains("publishDaysInvalidated"), http.bodies.getFirst());
  }

  @Test
  @DisplayName("signs the request, so AppSync's @aws_iam field will accept it")
  void signsTheRequest() {
    final FakeHttpClient http = new FakeHttpClient(200, "{\"data\":{}}", null);

    publisherUsing(http).publish(List.of("2026-09-14"));

    final var headers = http.requests.getFirst().httpRequest().headers();
    assertTrue(
        headers.containsKey("Authorization"),
        "unsigned requests are rejected: " + headers.keySet());
    assertTrue(
        headers.get("Authorization").getFirst().contains("AWS4-HMAC-SHA256"),
        headers.get("Authorization").toString());
    // The signing scope must name appsync, not the region alone - a request signed for another
    // service authenticates as nothing and fails at the API, well away from this code.
    assertTrue(
        headers.get("Authorization").getFirst().contains("/appsync/aws4_request"),
        headers.get("Authorization").toString());
  }

  @Test
  @DisplayName("an empty date list makes no call at all")
  void doesNotCallForNoDates() {
    final FakeHttpClient http = new FakeHttpClient(200, "{\"data\":{}}", null);

    publisherUsing(http).publish(List.of());

    assertTrue(http.bodies.isEmpty(), "nothing changed, so there is nothing to say");
  }

  @Test
  @DisplayName("a transport failure is swallowed, never propagated to the caller")
  void swallowsTransportFailures() {
    final FakeHttpClient http = new FakeHttpClient(0, "", new RuntimeException("connection reset"));

    // The write has already committed. Propagating this would turn a missed refresh into a
    // failed booking, at the exact moment AppSync is unhealthy.
    assertDoesNotThrow(() -> publisherUsing(http).publish(List.of("2026-09-14")));
  }

  @Test
  @DisplayName("a 200 carrying GraphQL errors is swallowed too")
  void swallowsGraphQlErrorsInsideA200() {
    // The shape a missing type-level auth directive produces: HTTP 200, errors in the body,
    // and nothing delivered to any subscriber. It must be survivable, and it must be logged -
    // this is the only side of that failure where anything is observable at all.
    final FakeHttpClient http =
        new FakeHttpClient(
            200,
            "{\"data\":null,\"errors\":[{\"errorType\":\"Unauthorized\","
                + "\"message\":\"Not Authorized to access dates on type Invalidation\"}]}",
            null);

    assertDoesNotThrow(() -> publisherUsing(http).publish(List.of("2026-09-14")));
  }

  @Test
  @DisplayName("a non-200 is swallowed")
  void swallowsNon200() {
    final FakeHttpClient http = new FakeHttpClient(403, "Forbidden", null);

    assertDoesNotThrow(() -> publisherUsing(http).publish(List.of("2026-09-14")));
  }
}
