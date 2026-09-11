package com.mootmaker.verify;

import module java.base;
import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Minimal HTTP client for executing GraphQL operations against the deployed AppSync API. */
class GraphQlClient {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** One token is fetched per test run and shared by every test class. */
  private static String cachedAccessToken;

  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final URI endpoint;
  private final String accessToken;

  GraphQlClient(final String endpoint, final String accessToken) {
    this.endpoint = URI.create(endpoint);
    this.accessToken = accessToken;
  }

  /**
   * Builds a client for the deployed API using the OAuth2 client_credentials flow: the
   * acceptance-test app client's id and secret are exchanged at the Cognito token endpoint for a
   * JWT access token, so no human user or password is involved. Reads GRAPHQL_API_URL,
   * COGNITO_TOKEN_URL, COGNITO_TEST_CLIENT_ID, COGNITO_TEST_CLIENT_SECRET and COGNITO_TEST_SCOPE
   * from the environment (exported by authenticate.sh from Terraform outputs).
   */
  static GraphQlClient fromEnvironment() {
    return new GraphQlClient(requireEnv("GRAPHQL_API_URL"), accessToken());
  }

  /**
   * The same token this client authenticates with, for a subscription to reuse. AppSync accepts it
   * over the realtime transport exactly as it does over HTTP, so a test does not need a second set
   * of credentials to prove a broadcast reaches a second client.
   */
  String accessTokenForSubscriptions() {
    return accessToken;
  }

  private static synchronized String accessToken() {
    if (cachedAccessToken == null) {
      cachedAccessToken = fetchAccessToken();
    }
    return cachedAccessToken;
  }

  private static String fetchAccessToken() {
    final String tokenUrl = requireEnv("COGNITO_TOKEN_URL");
    final String form =
        "grant_type=client_credentials"
            + "&client_id="
            + urlEncode(requireEnv("COGNITO_TEST_CLIENT_ID"))
            + "&client_secret="
            + urlEncode(requireEnv("COGNITO_TEST_CLIENT_SECRET"))
            + "&scope="
            + urlEncode(requireEnv("COGNITO_TEST_SCOPE"));

    final HttpRequest request =
        HttpRequest.newBuilder(URI.create(tokenUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form))
            .build();

    try (HttpClient client = HttpClient.newHttpClient()) {
      final HttpResponse<String> response =
          client.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        throw new IllegalStateException(
            "Cognito token endpoint returned HTTP "
                + response.statusCode()
                + ": "
                + response.body());
      }
      final JsonNode accessToken = OBJECT_MAPPER.readTree(response.body()).get("access_token");
      if (accessToken == null) {
        throw new IllegalStateException(
            "Cognito token response contained no access_token: " + response.body());
      }
      return accessToken.asText();
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to fetch a Cognito access token", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Cognito token request was interrupted", e);
    }
  }

  private static String urlEncode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String requireEnv(final String name) {
    final String value = System.getenv(name);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          name
              + " environment variable is required to run acceptance tests "
              + "against the deployed mootmaker API. Export it with `source authenticate.sh`.");
    }
    return value;
  }

  JsonNode execute(final String query) {
    return execute(query, Map.of());
  }

  JsonNode execute(final String query, final Map<String, Object> variables) {
    try {
      final String requestBody =
          OBJECT_MAPPER.writeValueAsString(Map.of("query", query, "variables", variables));

      final HttpRequest request =
          HttpRequest.newBuilder(endpoint)
              .header("Content-Type", "application/json")
              .header("Authorization", accessToken)
              .POST(HttpRequest.BodyPublishers.ofString(requestBody))
              .build();

      final HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      final JsonNode root = OBJECT_MAPPER.readTree(response.body());

      if (root.has("errors")) {
        throw new IllegalStateException("GraphQL request failed: " + root.get("errors"));
      }
      return root.get("data");
    } catch (final IOException e) {
      throw new IllegalStateException("Failed to execute GraphQL request", e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("GraphQL request was interrupted", e);
    }
  }
}
