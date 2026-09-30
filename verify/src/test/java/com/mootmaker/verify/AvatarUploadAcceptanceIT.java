package com.mootmaker.verify;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import module java.base;
import module java.net.http;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import net.datafaker.Faker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acceptance tests for the avatar upload flow: {@code requestAvatarUpload}, an HTTP PUT straight to
 * S3, {@code confirmAvatarUpload}, and {@code removeAvatar}.
 *
 * <p>These exist at this layer because the parts that matter most are exactly the parts a unit test
 * has to fake: the presigned URL being honoured by real S3, the resolver role's permissions, and -
 * above all - the image actually being <i>served</i>. {@code Person.avatarUrl} is an absolute URL
 * on a distribution this project owns, so this suite fetches it and checks what comes back, with no
 * webapp deployed. That an API-only environment can prove an avatar end to end is the property the
 * design was reshaped around.
 *
 * <p>The M2M tooling client carries the admin scope, so every call here takes the "admin" half of
 * "admin, or the caller's own person". The self half needs a real signed-in user and is covered by
 * the handler unit tests.
 */
class AvatarUploadAcceptanceIT {

  private static final Logger LOG = LoggerFactory.getLogger(AvatarUploadAcceptanceIT.class);

  private static final String CREATE_PERSON_MUTATION =
      "mutation CreatePerson($name: String!) { createPerson(name: $name) { person { id avatarUrl }"
          + " errors } }";
  private static final String REQUEST_UPLOAD_MUTATION =
      "mutation Request($personId: ID!, $contentType: String!, $contentLength: Int!) {"
          + " requestAvatarUpload(personId: $personId, contentType: $contentType, contentLength:"
          + " $contentLength) { upload { uploadId url contentType contentLength expiresAt } errors"
          + " } }";
  private static final String CONFIRM_UPLOAD_MUTATION =
      "mutation Confirm($personId: ID!, $uploadId: ID!) { confirmAvatarUpload(personId:"
          + " $personId, uploadId: $uploadId) { person { id name avatarUrl } errors } }";
  private static final String REMOVE_AVATAR_MUTATION =
      "mutation Remove($personId: ID!) { removeAvatar(personId: $personId) { person { id"
          + " avatarUrl } errors } }";
  private static final String PEOPLE_QUERY = "query { workspace { people { id avatarUrl } } }";

  private static GraphQlClient client;
  private static HttpClient http;
  private static Faker faker;

  @BeforeAll
  static void setUpClient() {
    client = GraphQlClient.fromEnvironment();
    http = HttpClient.newHttpClient();
    faker = new Faker();
  }

  // --- Helpers --------------------------------------------------------------------------

  private static String createPerson() {
    final JsonNode person =
        client
            .execute(CREATE_PERSON_MUTATION, Map.of("name", faker.name().fullName()))
            .get("createPerson")
            .get("person");
    assertThat("a new person starts with no avatar", person.get("avatarUrl").isNull(), is(true));
    return person.get("id").asText();
  }

  private static JsonNode requestUpload(
      final String personId, final String contentType, final int contentLength) {
    return client
        .execute(
            REQUEST_UPLOAD_MUTATION,
            Map.of(
                "personId", personId, "contentType", contentType, "contentLength", contentLength))
        .get("requestAvatarUpload");
  }

  private static JsonNode confirmUpload(final String personId, final String uploadId) {
    return client
        .execute(CONFIRM_UPLOAD_MUTATION, Map.of("personId", personId, "uploadId", uploadId))
        .get("confirmAvatarUpload");
  }

  /** The PUT a browser or mootmaker-demo-data makes: no Authorization header, just the bytes. */
  private static HttpResponse<String> put(
      final String url, final String contentType, final byte[] body) {
    return send(
        HttpRequest.newBuilder(URI.create(url))
            .header("Content-Type", contentType)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static HttpResponse<byte[]> get(final String url) {
    return send(
        HttpRequest.newBuilder(URI.create(url)).GET().build(),
        HttpResponse.BodyHandlers.ofByteArray());
  }

  private static <T> HttpResponse<T> send(
      final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
    try {
      return http.send(request, handler);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** Stages and confirms {@code image} as this person's avatar, returning the resulting URL. */
  private static String setAvatar(final String personId, final byte[] image) {
    final JsonNode requested = requestUpload(personId, "image/png", image.length);
    assertThat(requested.get("errors").size(), equalTo(0));
    final JsonNode upload = requested.get("upload");

    final HttpResponse<String> putResponse = put(upload.get("url").asText(), "image/png", image);
    assertThat("S3 PUT: " + putResponse.body(), putResponse.statusCode(), equalTo(200));

    final JsonNode confirmed = confirmUpload(personId, upload.get("uploadId").asText());
    assertThat(confirmed.get("errors").size(), equalTo(0));
    return confirmed.get("person").get("avatarUrl").asText();
  }

  private static String avatarUrlFromWorkspace(final String personId) {
    for (final JsonNode person : client.execute(PEOPLE_QUERY).get("workspace").get("people")) {
      if (person.get("id").asText().equals(personId)) {
        return person.get("avatarUrl").isNull() ? null : person.get("avatarUrl").asText();
      }
    }
    throw new AssertionError("person " + personId + " is missing from workspace.people");
  }

  /** A distinct image per seed, so two avatars in one test are genuinely different bytes. */
  private static byte[] png(final int size, final Color color) {
    final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
    final Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(color);
      graphics.fillRect(0, 0, size, size);
      graphics.setColor(Color.WHITE);
      graphics.fillOval(size / 4, size / 4, size / 2, size / 2);
    } finally {
      graphics.dispose();
    }
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      ImageIO.write(image, "png", bytes);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  // --- Tests ----------------------------------------------------------------------------

  @Test
  void anUploadedAvatarIsNormalisedStoredAndServed() throws IOException {
    LOG.info("Resetting the database before the test");
    DatabaseReset.reset();
    final String personId = createPerson();
    final byte[] source = png(400, Color.ORANGE);

    LOG.info("Uploading and confirming a {}-byte PNG for person '{}'", source.length, personId);
    final String avatarUrl = setAvatar(personId, source);

    LOG.info("Avatar URL is {}", avatarUrl);
    assertThat(avatarUrl, startsWith("https://avatars."));
    assertThat(avatarUrl, endsWith("/v1/" + personId + "/" + sha256Hex(source) + ".jpg"));
    assertThat(
        "a later read must return the same URL the mutation did",
        avatarUrlFromWorkspace(personId),
        equalTo(avatarUrl));

    LOG.info("Fetching the avatar through the distribution");
    final HttpResponse<byte[]> served = get(avatarUrl);
    assertThat(served.statusCode(), equalTo(200));
    assertThat(served.headers().firstValue("Content-Type").orElse(""), equalTo("image/jpeg"));
    assertThat(
        served.headers().firstValue("Cache-Control").orElse(""),
        equalTo("public, max-age=31536000, immutable"));
    final BufferedImage image = ImageIO.read(new ByteArrayInputStream(served.body()));
    assertThat("the served bytes must decode as an image", image != null, is(true));
    assertThat(image.getWidth(), equalTo(256));
    assertThat(image.getHeight(), equalTo(256));
    assertThat(
        "what is served must be re-encoded, never the uploaded bytes",
        Arrays.equals(served.body(), source),
        is(false));
  }

  /**
   * The failure this whole refactor follows: a missing image answered with a web page at status
   * 200, which a browser treats as a broken image and MUI quietly turns into initials. This
   * distribution has no error-page fallback, so a missing key has to be a real refusal.
   */
  @Test
  void aMissingAvatarIsARealErrorNotAPageAtStatus200() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final String avatarUrl = setAvatar(personId, png(128, Color.MAGENTA));
    final String missing = avatarUrl.replaceAll("[0-9a-f]{64}\\.jpg$", "0".repeat(64) + ".jpg");
    assertThat("the fixture must name a different object", missing, not(equalTo(avatarUrl)));

    final HttpResponse<byte[]> response = get(missing);

    // 403 rather than 404 if the distribution's read access does not include listing the bucket,
    // which is S3 declining to say whether the key exists. Either is a refusal; 200 is the bug.
    assertThat(response.statusCode(), anyOf(equalTo(403), equalTo(404)));
    assertThat(
        response.headers().firstValue("Content-Type").orElse(""), not(containsString("html")));
  }

  @Test
  void aStagedUploadIsNotReachableThroughTheDistribution() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final byte[] source = png(128, Color.CYAN);
    final JsonNode upload = requestUpload(personId, "image/png", source.length).get("upload");
    assertThat(put(upload.get("url").asText(), "image/png", source).statusCode(), equalTo(200));
    // Confirm it, purely to learn the distribution's host from the resulting URL.
    final String avatarUrl =
        confirmUpload(personId, upload.get("uploadId").asText())
            .get("person")
            .get("avatarUrl")
            .asText();
    final String host = URI.create(avatarUrl).getHost();

    final HttpResponse<byte[]> response =
        get("https://" + host + "/uploads/" + personId + "/" + upload.get("uploadId").asText());

    assertThat(response.statusCode(), anyOf(equalTo(403), equalTo(404)));
  }

  @Test
  void replacingAnAvatarChangesTheUrlAndRemovingItClearsIt() {
    DatabaseReset.reset();
    final String personId = createPerson();

    final String first = setAvatar(personId, png(128, Color.RED));
    final String second = setAvatar(personId, png(128, Color.BLUE));
    assertThat("a different image must be a different URL", second, not(equalTo(first)));
    assertThat(avatarUrlFromWorkspace(personId), equalTo(second));
    assertThat(get(second).statusCode(), equalTo(200));

    LOG.info("Removing the avatar");
    final JsonNode removed =
        client.execute(REMOVE_AVATAR_MUTATION, Map.of("personId", personId)).get("removeAvatar");
    assertThat(removed.get("errors").size(), equalTo(0));
    assertThat(removed.get("person").get("avatarUrl").isNull(), is(true));
    assertThat(avatarUrlFromWorkspace(personId) == null, is(true));
  }

  @Test
  void confirmingTheSameUploadTwiceSucceedsTwice() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final byte[] source = png(128, Color.GREEN);
    final JsonNode upload = requestUpload(personId, "image/png", source.length).get("upload");
    assertThat(put(upload.get("url").asText(), "image/png", source).statusCode(), equalTo(200));
    final String uploadId = upload.get("uploadId").asText();

    final JsonNode first = confirmUpload(personId, uploadId);
    final JsonNode second = confirmUpload(personId, uploadId);

    assertThat(second.get("errors").size(), equalTo(0));
    assertThat(
        second.get("person").get("avatarUrl").asText(),
        equalTo(first.get("person").get("avatarUrl").asText()));
  }

  // --- Rejections: structured errors, never a 500 -----------------------------------------

  @Test
  void anUploadThatIsNotAnImageIsRejectedWithATypedError() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final byte[] notAnImage =
        "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
    // Declared as a PNG, which is all requestAvatarUpload can check - and S3 only verifies the
    // header matches the signature, not that the bytes match the header.
    final JsonNode upload = requestUpload(personId, "image/png", notAnImage.length).get("upload");
    assertThat(put(upload.get("url").asText(), "image/png", notAnImage).statusCode(), equalTo(200));

    final JsonNode confirmed = confirmUpload(personId, upload.get("uploadId").asText());

    assertThat(confirmed.get("person").isNull(), is(true));
    assertThat(confirmed.get("errors").get(0).asText(), equalTo(AvatarError.NotAnImage.name()));
    assertThat(avatarUrlFromWorkspace(personId) == null, is(true));
  }

  @Test
  void confirmingWithoutUploadingIsRejectedWithATypedError() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final JsonNode upload = requestUpload(personId, "image/png", 1000).get("upload");

    final JsonNode confirmed = confirmUpload(personId, upload.get("uploadId").asText());

    // Depends on the resolver role holding s3:ListBucket: without it S3 answers a missing key with
    // AccessDenied rather than NoSuchKey, and this would surface as a resolver error instead.
    assertThat(confirmed.get("person").isNull(), is(true));
    assertThat(confirmed.get("errors").get(0).asText(), equalTo(AvatarError.UploadNotFound.name()));
  }

  @Test
  void aDeclaredUploadOutsideTheLimitsIsRejectedBeforeAnyUrlIsIssued() {
    DatabaseReset.reset();
    final String personId = createPerson();

    final JsonNode tooLarge = requestUpload(personId, "image/png", 2 * 1024 * 1024 + 1);
    assertThat(tooLarge.get("upload").isNull(), is(true));
    assertThat(tooLarge.get("errors").get(0).asText(), equalTo(AvatarError.UploadTooLarge.name()));

    final JsonNode wrongType = requestUpload(personId, "image/webp", 1000);
    assertThat(wrongType.get("upload").isNull(), is(true));
    assertThat(
        wrongType.get("errors").get(0).asText(),
        equalTo(AvatarError.UnsupportedContentType.name()));

    final JsonNode noSuchPerson = requestUpload("NoSuchId", "image/png", 1000);
    assertThat(
        noSuchPerson.get("errors").get(0).asText(), equalTo(AvatarError.PersonNotFound.name()));
  }

  /**
   * The size ceiling is only real if S3 enforces it. The declared length is part of the signature,
   * so a body of any other size must be refused by S3 itself - before this API sees a byte.
   */
  @Test
  void s3RefusesAPutThatDoesNotMatchWhatWasDeclared() {
    DatabaseReset.reset();
    final String personId = createPerson();
    final byte[] source = png(128, Color.YELLOW);

    final JsonNode declaredSmaller =
        requestUpload(personId, "image/png", source.length - 1).get("upload");
    assertThat(
        "a body larger than declared",
        put(declaredSmaller.get("url").asText(), "image/png", source).statusCode(),
        equalTo(403));

    final JsonNode declaredPng = requestUpload(personId, "image/png", source.length).get("upload");
    assertThat(
        "a content type other than declared",
        put(declaredPng.get("url").asText(), "image/jpeg", source).statusCode(),
        equalTo(403));
  }
}
