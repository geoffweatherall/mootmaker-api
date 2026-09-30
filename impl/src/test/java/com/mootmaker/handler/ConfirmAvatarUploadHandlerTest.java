package com.mootmaker.handler;

import static com.mootmaker.handler.AvatarHandlerFixtures.AVATAR_URLS;
import static com.mootmaker.handler.AvatarHandlerFixtures.BASE_URL;
import static com.mootmaker.handler.AvatarHandlerFixtures.TABLE;
import static com.mootmaker.handler.AvatarHandlerFixtures.asAdmin;
import static com.mootmaker.handler.AvatarHandlerFixtures.asPerson;
import static com.mootmaker.handler.AvatarHandlerFixtures.fullyPopulated;
import static com.mootmaker.handler.AvatarHandlerFixtures.map;
import static com.mootmaker.handler.AvatarHandlerFixtures.seed;
import static com.mootmaker.handler.AvatarHandlerFixtures.store;
import static com.mootmaker.handler.AvatarHandlerFixtures.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.avatar.AvatarImage;
import com.mootmaker.testsupport.FakeDynamoDbClient;
import com.mootmaker.testsupport.FakeS3Client;
import com.mootmaker.testsupport.TestImages;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

class ConfirmAvatarUploadHandlerTest {

  private static final String UPLOAD = "UPLOAD01";
  private static final String SECOND_UPLOAD = "UPLOAD02";

  private final FakeDynamoDbClient dynamo = new FakeDynamoDbClient();
  private final FakeS3Client s3 = new FakeS3Client();
  private final ConfirmAvatarUploadHandler handler =
      new ConfirmAvatarUploadHandler(dynamo, TABLE, store(s3), AVATAR_URLS);

  private static Map<String, Object> arguments(final String personId, final String uploadId) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("personId", personId);
    arguments.put("uploadId", uploadId);
    return arguments;
  }

  private Map<String, Object> invoke(final Map<String, Object> event) {
    return map(handler.handleRequest(event, null));
  }

  private Map<String, Object> confirm(final String personId, final String uploadId) {
    return invoke(asAdmin(arguments(personId, uploadId)));
  }

  private void stage(final String personId, final String uploadId, final byte[] bytes) {
    s3.stage("uploads/" + personId + "/" + uploadId, bytes, "image/png");
  }

  private static String sha256Hex(final byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private Set<String> servedKeys() {
    return s3.objects.keySet().stream()
        .filter(key -> key.startsWith("avatars/"))
        .collect(Collectors.toSet());
  }

  // --- The round trip -------------------------------------------------------------------

  @Test
  void setsThePersonsAvatarToTheNormalisedImage() {
    seed(dynamo, fullyPopulated("person-1", null));
    final byte[] source = TestImages.png(400, 400);
    stage("person-1", UPLOAD, source);

    final Map<String, Object> result = confirm("person-1", UPLOAD);

    final String hash = sha256Hex(source);
    assertEquals(List.of(), result.get("errors"));
    assertEquals(
        BASE_URL + "/v1/person-1/" + hash + ".jpg", map(result.get("person")).get("avatarUrl"));
    assertEquals("v1/person-1/" + hash, stored(dynamo, "person-1").avatarUrl());

    final FakeS3Client.StoredObject served = s3.objects.get("avatars/v1/person-1/" + hash + ".jpg");
    assertEquals("image/jpeg", served.contentType());
    assertEquals("public, max-age=31536000, immutable", served.cacheControl());
    final BufferedImage image = TestImages.decode(served.bytes());
    assertEquals(AvatarImage.OUTPUT_SIZE, image.getWidth());
    assertEquals(AvatarImage.OUTPUT_SIZE, image.getHeight());
  }

  @Test
  @DisplayName("what is served is never the bytes that were uploaded")
  void servesReEncodedBytesNotTheUpload() {
    seed(dynamo, fullyPopulated("person-1", null));
    final byte[] source = TestImages.jpeg(256, 256);
    stage("person-1", UPLOAD, source);

    confirm("person-1", UPLOAD);

    final byte[] served = s3.objects.get(servedKeys().iterator().next()).bytes();
    assertTrue(!Arrays.equals(source, served));
  }

  @Test
  void leavesEveryOtherFieldOfThePersonUntouched() {
    seed(dynamo, fullyPopulated("person-1", null));
    final byte[] source = TestImages.png(128, 128);
    stage("person-1", UPLOAD, source);

    confirm("person-1", UPLOAD);

    assertEquals(
        fullyPopulated("person-1", "v1/person-1/" + sha256Hex(source)), stored(dynamo, "person-1"));
  }

  @Test
  void aPersonMayConfirmTheirOwnUpload() {
    seed(dynamo, fullyPopulated("person-1", null));
    stage("person-1", UPLOAD, TestImages.png(128, 128));

    final Map<String, Object> result = invoke(asPerson("person-1", arguments("person-1", UPLOAD)));

    assertEquals(List.of(), result.get("errors"));
  }

  // --- At most one avatar per person ----------------------------------------------------

  @Test
  @DisplayName("setting an avatar twice leaves exactly one object under the person's prefix")
  void replacingAnAvatarDeletesThePreviousOne() {
    seed(dynamo, fullyPopulated("person-1", null));
    final byte[] first = TestImages.png(128, 128);
    final byte[] second = TestImages.png(200, 200);
    stage("person-1", UPLOAD, first);
    stage("person-1", SECOND_UPLOAD, second);

    confirm("person-1", UPLOAD);
    confirm("person-1", SECOND_UPLOAD);

    assertEquals(Set.of("avatars/v1/person-1/" + sha256Hex(second) + ".jpg"), servedKeys());
    assertEquals("v1/person-1/" + sha256Hex(second), stored(dynamo, "person-1").avatarUrl());
  }

  /**
   * The same image yields the same key, so a sweep that deleted "whatever was there before" would
   * delete the avatar it had just set - leaving a Person pointing at nothing.
   */
  @Test
  @DisplayName("re-uploading the same image does not delete the avatar it just set")
  void reUploadingTheSameImageKeepsIt() {
    seed(dynamo, fullyPopulated("person-1", null));
    final byte[] source = TestImages.png(128, 128);
    stage("person-1", UPLOAD, source);
    stage("person-1", SECOND_UPLOAD, source);

    confirm("person-1", UPLOAD);
    final Map<String, Object> result = confirm("person-1", SECOND_UPLOAD);

    assertEquals(List.of(), result.get("errors"));
    assertEquals(Set.of("avatars/v1/person-1/" + sha256Hex(source) + ".jpg"), servedKeys());
  }

  @Test
  @DisplayName("confirming the same upload twice succeeds twice - a lost response can be retried")
  void confirmIsIdempotent() {
    seed(dynamo, fullyPopulated("person-1", null));
    stage("person-1", UPLOAD, TestImages.png(128, 128));

    final Map<String, Object> first = confirm("person-1", UPLOAD);
    final Map<String, Object> second = confirm("person-1", UPLOAD);

    assertEquals(List.of(), second.get("errors"));
    assertEquals(first.get("person"), second.get("person"));
    assertEquals(1, servedKeys().size());
  }

  @Test
  @DisplayName("an object orphaned by an earlier interrupted set is swept up by the next one")
  void sweepsUpAnOrphanTheRecordDoesNotKnowAbout() {
    seed(dynamo, fullyPopulated("person-1", null));
    s3.stage("avatars/v1/person-1/orphan.jpg", new byte[] {1}, "image/jpeg");
    final byte[] source = TestImages.png(128, 128);
    stage("person-1", UPLOAD, source);

    confirm("person-1", UPLOAD);

    assertEquals(Set.of("avatars/v1/person-1/" + sha256Hex(source) + ".jpg"), servedKeys());
  }

  @Test
  @DisplayName("two people with the identical image hold separate objects, and keep them")
  void anotherPersonsIdenticalImageIsUntouched() {
    seed(dynamo, fullyPopulated("person-1", null), fullyPopulated("person-2", null));
    final byte[] shared = TestImages.png(128, 128);
    stage("person-1", UPLOAD, shared);
    stage("person-2", UPLOAD, shared);
    stage("person-1", SECOND_UPLOAD, TestImages.png(200, 200));

    confirm("person-1", UPLOAD);
    confirm("person-2", UPLOAD);
    confirm("person-1", SECOND_UPLOAD);

    assertTrue(servedKeys().contains("avatars/v1/person-2/" + sha256Hex(shared) + ".jpg"));
    assertEquals(2, servedKeys().size());
  }

  // --- Rejections -----------------------------------------------------------------------

  @Test
  @DisplayName("a rejected upload is a typed error, and leaves the existing avatar alone")
  void rejectsBytesThatAreNotAnImage() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/existing"));
    s3.stage("avatars/v1/person-1/existing.jpg", new byte[] {1}, "image/jpeg");
    stage("person-1", UPLOAD, "<html>not an image</html>".getBytes(StandardCharsets.UTF_8));

    final Map<String, Object> result = confirm("person-1", UPLOAD);

    assertNull(result.get("person"));
    assertEquals(List.of("NotAnImage"), result.get("errors"));
    assertEquals("v1/person-1/existing", stored(dynamo, "person-1").avatarUrl());
    assertEquals(Set.of("avatars/v1/person-1/existing.jpg"), servedKeys());
  }

  @Test
  void rejectsAnImageThatIsTooSmall() {
    seed(dynamo, fullyPopulated("person-1", null));
    stage("person-1", UPLOAD, TestImages.png(32, 32));

    assertEquals(List.of("ImageTooSmall"), confirm("person-1", UPLOAD).get("errors"));
    assertTrue(servedKeys().isEmpty());
  }

  @Test
  void rejectsAnOversizedStagedObject() {
    seed(dynamo, fullyPopulated("person-1", null));
    stage("person-1", UPLOAD, TestImages.png(128, 128));
    s3.reportedContentLength = AvatarImage.MAX_UPLOAD_BYTES + 1;

    assertEquals(List.of("UploadTooLarge"), confirm("person-1", UPLOAD).get("errors"));
    assertTrue(servedKeys().isEmpty());
  }

  @Test
  void rejectsAnUploadThatWasNeverMade() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result = confirm("person-1", UPLOAD);

    assertNull(result.get("person"));
    assertEquals(List.of("UploadNotFound"), result.get("errors"));
  }

  @Test
  @DisplayName("one person's staged upload cannot be confirmed as another's avatar")
  void rejectsAnUploadStagedForSomeoneElse() {
    seed(dynamo, fullyPopulated("person-1", null), fullyPopulated("person-2", null));
    stage("person-2", UPLOAD, TestImages.png(128, 128));

    assertEquals(List.of("UploadNotFound"), confirm("person-1", UPLOAD).get("errors"));
  }

  @Test
  @DisplayName("an uploadId this API could not have issued never reaches an object key")
  void rejectsAMalformedUploadId() {
    seed(dynamo, fullyPopulated("person-1", null));
    // Would resolve, if it were used as a key, to a real served object.
    s3.stage("uploads/person-1/../../avatars/v1/person-1/x", TestImages.png(128, 128), "image/png");

    for (final String uploadId :
        Arrays.asList("../../avatars/v1/person-1/x", "short", "has space", "", null)) {
      assertEquals(
          List.of("UploadNotFound"),
          confirm("person-1", uploadId).get("errors"),
          String.valueOf(uploadId));
    }
    assertTrue(servedKeys().isEmpty());
  }

  @Test
  void rejectsAnUnknownPerson() {
    stage("ghost", UPLOAD, TestImages.png(128, 128));

    final Map<String, Object> result = confirm("ghost", UPLOAD);

    assertEquals(List.of("PersonNotFound"), result.get("errors"));
    assertTrue(servedKeys().isEmpty());
    assertTrue(dynamo.tables.getOrDefault(TABLE, List.of()).isEmpty(), "no partial Person created");
  }

  /**
   * The person is deleted after the existence check but before the record is updated. The update's
   * attribute_exists guard refuses to conjure a Person from an avatarUrl alone, and the image
   * already written must not be left behind under a prefix that now belongs to nobody.
   */
  @Test
  @DisplayName("a person deleted mid-confirm gets no resurrected record and no orphaned image")
  void personDeletedMidFlightLeavesNothingBehind() {
    final FakeS3Client racing =
        new FakeS3Client() {
          @Override
          public synchronized PutObjectResponse putObject(
              final PutObjectRequest request, final RequestBody body) {
            final PutObjectResponse response = super.putObject(request, body);
            dynamo.tables.get(TABLE).clear();
            return response;
          }
        };
    seed(dynamo, fullyPopulated("person-1", null));
    racing.stage("uploads/person-1/" + UPLOAD, TestImages.png(128, 128), "image/png");
    final ConfirmAvatarUploadHandler racingHandler =
        new ConfirmAvatarUploadHandler(dynamo, TABLE, store(racing), AVATAR_URLS);

    final Map<String, Object> result =
        map(racingHandler.handleRequest(asAdmin(arguments("person-1", UPLOAD)), null));

    assertEquals(List.of("PersonNotFound"), result.get("errors"));
    assertTrue(dynamo.tables.get(TABLE).isEmpty());
    assertTrue(racing.objects.keySet().stream().noneMatch(key -> key.startsWith("avatars/")));
  }

  // --- Authorisation --------------------------------------------------------------------

  @Test
  void aNonAdminMayNotConfirmForSomeoneElse() {
    seed(dynamo, fullyPopulated("person-1", null));
    stage("person-1", UPLOAD, TestImages.png(128, 128));

    assertThrows(
        IllegalStateException.class,
        () -> invoke(asPerson("person-2", arguments("person-1", UPLOAD))));
    assertNull(stored(dynamo, "person-1").avatarUrl());
    assertTrue(servedKeys().isEmpty());
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    final Map<String, Object> event = asAdmin(arguments("person-1", UPLOAD));
    event.remove("identity");

    assertThrows(IllegalStateException.class, () -> invoke(event));
  }
}
