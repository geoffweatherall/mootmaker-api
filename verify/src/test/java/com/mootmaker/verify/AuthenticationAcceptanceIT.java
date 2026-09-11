package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import module java.base;
import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests proving the API cannot be used without a valid Cognito JWT: requests with no
 * token, a malformed token, or a forged token must be rejected, and no query or mutation may run.
 */
class AuthenticationAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticationAcceptanceIT.class);

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private static final String ROOMS_QUERY =
      "{\"query\":\"query { workspace { rooms { id name capacity } } }\"}";
  private static final String CREATE_PERSON_MUTATION =
      "{\"query\":\"mutation { createPerson(person: { name: \\\"Intruder\\\" }) { person { id name"
          + " } errors } }\"}";

  private static URI endpoint;
  private static HttpClient httpClient;

  @BeforeAll
  static void setUp() {
    final String url = System.getenv("GRAPHQL_API_URL");
    if (url == null || url.isBlank()) {
      throw new IllegalStateException(
          "GRAPHQL_API_URL environment variable is required. "
              + "Export it with `source authenticate.sh`.");
    }
    endpoint = URI.create(url);
    httpClient = HttpClient.newHttpClient();
  }

  @Test
  void queryWithoutTokenIsRejected() {
    LOG.info("Sending a rooms query with no Authorization header");
    final HttpResponse<String> response = post(ROOMS_QUERY, null);

    assertUnauthorized(response);
  }

  @Test
  void mutationWithoutTokenIsRejected() {
    LOG.info("Sending a createPerson mutation with no Authorization header");
    final HttpResponse<String> response = post(CREATE_PERSON_MUTATION, null);

    assertUnauthorized(response);
  }

  @Test
  void malformedTokenIsRejected() {
    LOG.info("Sending a rooms query with a malformed Authorization header");
    final HttpResponse<String> response = post(ROOMS_QUERY, "not-a-jwt");

    assertUnauthorized(response);
  }

  @Test
  void forgedTokenIsRejected() {
    LOG.info("Sending a rooms query with a structurally valid but forged JWT");
    final String forgedToken =
        base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}")
            + "."
            + base64Url("{\"sub\":\"intruder\",\"token_use\":\"access\",\"exp\":9999999999}")
            + "."
            + base64Url("forged-signature");
    final HttpResponse<String> response = post(ROOMS_QUERY, forgedToken);

    assertUnauthorized(response);
  }

  @Test
  void validTokenIsAccepted() {
    LOG.info("Checking the same rooms query succeeds with a valid client_credentials token");
    final JsonNode data =
        GraphQlClient.fromEnvironment()
            .execute("query { workspace { rooms { id name capacity } } }");

    assertThat(data.get("workspace").get("rooms").isArray(), is(true));
  }

  private static void assertUnauthorized(final HttpResponse<String> response) {
    LOG.info("Received HTTP {} with body {}", response.statusCode(), response.body());
    assertThat(response.statusCode(), equalTo(401));

    final JsonNode root = readJson(response.body());
    assertThat(
        root.path("errors").get(0).path("errorType").asText(), equalTo("UnauthorizedException"));
    assertThat(root.get("data"), nullValue());
  }

  private static HttpResponse<String> post(final String body, final String authorizationHeader) {
    try {
      final HttpRequest.Builder builder =
          HttpRequest.newBuilder(endpoint)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body));
      if (authorizationHeader != null) {
        builder.header("Authorization", authorizationHeader);
      }
      return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to execute GraphQL request", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GraphQL request was interrupted", e);
    }
  }

  private static JsonNode readJson(final String body) {
    try {
      return OBJECT_MAPPER.readTree(body);
    } catch (final IOException e) {
      throw new IllegalStateException("Response was not JSON: " + body, e);
    }
  }

  private static String base64Url(final String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }
}
