package com.mootmaker.verify;

import module java.base;
import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A subscription to the deployed AppSync API, over AppSync's own realtime protocol.
 *
 * <p><b>Hand-rolled rather than using a GraphQL client library, deliberately.</b> AppSync does not
 * speak {@code graphql-transport-ws} - it refuses that subprotocol outright (verified) - so the
 * usual libraries do not apply. Its protocol is negotiated as {@code graphql-ws} but is AWS's own:
 * auth travels in a base64 query parameter, the client sends {@code connection_init} and waits for
 * {@code connection_ack}, then sends {@code start} carrying the query as a JSON STRING inside
 * {@code payload.data}, and waits for {@code start_ack} before the subscription is live.
 *
 * <p>Publishing before {@code start_ack} is a real race and the reason {@link #awaitReady()}
 * exists: a broadcast sent between {@code start} and {@code start_ack} is not delivered, and the
 * test would fail for a reason that has nothing to do with the code under test.
 *
 * <p>The realtime URL is derived from {@code GRAPHQL_API_URL} rather than exported separately, but
 * the path differs from the host's: on a custom domain the realtime endpoint is {@code
 * /graphql/realtime}, where the raw AppSync realtime host serves {@code /graphql}. Verified both
 * ways - {@code /graphql} on the custom domain fails to connect at all.
 */
final class AppSyncSubscription implements AutoCloseable {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Duration READY_TIMEOUT = Duration.ofSeconds(30);

  private final WebSocket socket;
  private final CountDownLatch ready = new CountDownLatch(1);
  private final BlockingQueue<JsonNode> messages = new LinkedBlockingQueue<>();
  private final List<String> protocolErrors = new CopyOnWriteArrayList<>();

  private AppSyncSubscription(final String query, final String endpoint, final String accessToken) {
    final URI http = URI.create(endpoint);
    final String host = http.getHost();
    final Map<String, String> authHeader = Map.of("host", host, "Authorization", accessToken);
    final String encodedHeader = base64(authHeader);

    final URI realtime =
        URI.create(
            "wss://"
                + host
                + http.getPath()
                + "/realtime"
                + "?header="
                + encodedHeader
                + "&payload="
                + base64(Map.of()));

    this.socket =
        HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .subprotocols("graphql-ws")
            .buildAsync(realtime, new Listener(query, encodedHeader))
            .join();
    this.socket.sendText("{\"type\":\"connection_init\"}", true);
  }

  static AppSyncSubscription open(
      final String query, final String endpoint, final String accessToken) {
    return new AppSyncSubscription(query, endpoint, accessToken);
  }

  /** Blocks until the subscription is live. Publishing before this returns loses the broadcast. */
  void awaitReady() throws InterruptedException {
    if (!ready.await(READY_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
      throw new IllegalStateException(
          "AppSync never acknowledged the subscription within "
              + READY_TIMEOUT
              + ". Protocol errors seen: "
              + protocolErrors);
    }
  }

  /** The next broadcast, or null if none arrives within the timeout. */
  JsonNode awaitMessage(final Duration timeout) throws InterruptedException {
    return messages.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Asserting that NOTHING arrives needs its own wait: there is no event to poll for, so the only
   * honest way is to give a broadcast time to turn up and confirm it did not.
   */
  boolean receivedNothingWithin(final Duration timeout) throws InterruptedException {
    return awaitMessage(timeout) == null;
  }

  List<String> protocolErrors() {
    return List.copyOf(protocolErrors);
  }

  @Override
  public void close() {
    socket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
  }

  private static String base64(final Object value) {
    try {
      return Base64.getEncoder().encodeToString(MAPPER.writeValueAsBytes(value));
    } catch (final Exception e) {
      throw new IllegalStateException("Failed to encode the AppSync auth header", e);
    }
  }

  /** Accumulates fragments: onText may deliver one JSON message across several calls. */
  private final class Listener implements WebSocket.Listener {

    private final StringBuilder buffer = new StringBuilder();
    private final String query;
    private final String encodedHeader;

    Listener(final String query, final String encodedHeader) {
      this.query = query;
      this.encodedHeader = encodedHeader;
    }

    @Override
    public CompletionStage<?> onText(
        final WebSocket webSocket, final CharSequence data, final boolean last) {
      webSocket.request(1);
      buffer.append(data);
      if (!last) {
        return null;
      }
      final String message = buffer.toString();
      buffer.setLength(0);
      try {
        handle(webSocket, MAPPER.readTree(message));
      } catch (final Exception e) {
        protocolErrors.add("Unparseable message: " + message);
      }
      return null;
    }

    private void handle(final WebSocket webSocket, final JsonNode message) throws Exception {
      switch (message.path("type").asText()) {
        case "connection_ack" -> {
          final Map<String, Object> start =
              Map.of(
                  "id", UUID.randomUUID().toString(),
                  "type", "start",
                  "payload",
                      Map.of(
                          "data",
                              MAPPER.writeValueAsString(
                                  Map.of("query", query, "variables", Map.of())),
                          "extensions",
                              Map.of(
                                  "authorization",
                                  MAPPER.readTree(Base64.getDecoder().decode(encodedHeader)))));
          webSocket.sendText(MAPPER.writeValueAsString(start), true);
        }
        case "start_ack" -> ready.countDown();
        case "data" -> messages.add(message.path("payload"));
        // Both carry the failure modes worth reporting: an unauthorised subscribe, or a
        // malformed start. Collected rather than thrown - this runs on the client's own
        // thread, where an exception would be swallowed and the test would just time out.
        case "error", "connection_error" -> protocolErrors.add(message.toString());
        default -> {}
      }
    }

    @Override
    public void onError(final WebSocket webSocket, final Throwable error) {
      protocolErrors.add("WebSocket error: " + error);
    }
  }
}
