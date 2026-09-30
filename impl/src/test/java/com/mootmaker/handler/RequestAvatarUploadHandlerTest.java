package com.mootmaker.handler;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestAvatarUploadHandlerTest {

  private final FakeDynamoDbClient dynamo = new FakeDynamoDbClient();
  private final FakeS3Client s3 = new FakeS3Client();
  private final RequestAvatarUploadHandler handler =
      new RequestAvatarUploadHandler(dynamo, TABLE, store(s3));

  private static Map<String, Object> arguments(
      final String personId, final String contentType, final Object contentLength) {
    final Map<String, Object> arguments = new HashMap<>();
    arguments.put("personId", personId);
    arguments.put("contentType", contentType);
    arguments.put("contentLength", contentLength);
    return arguments;
  }

  private Map<String, Object> invoke(final Map<String, Object> event) {
    return map(handler.handleRequest(event, null));
  }

  @Test
  void returnsAPresignedUploadIntoThePersonsStagingPrefix() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result = invoke(asAdmin(arguments("person-1", "image/png", 1234)));

    assertEquals(List.of(), result.get("errors"));
    final Map<String, Object> upload = map(result.get("upload"));
    final String uploadId = (String) upload.get("uploadId");
    assertEquals(8, uploadId.length());
    assertEquals("/uploads/person-1/" + uploadId, URI.create((String) upload.get("url")).getPath());
    assertEquals("image/png", upload.get("contentType"));
    assertEquals(1234L, upload.get("contentLength"));
    assertTrue(
        Instant.parse((String) upload.get("expiresAt")).isAfter(Instant.now()),
        "expiresAt must be a parseable instant in the future");
  }

  @Test
  @DisplayName("requesting an upload changes nothing - no object, and no avatar on the person")
  void changesNothing() {
    seed(dynamo, fullyPopulated("person-1", "v1/person-1/existing"));

    invoke(asAdmin(arguments("person-1", "image/png", 1234)));

    assertTrue(s3.objects.isEmpty());
    assertEquals(fullyPopulated("person-1", "v1/person-1/existing"), stored(dynamo, "person-1"));
  }

  @Test
  void aPersonMayRequestAnUploadForThemselves() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result =
        invoke(asPerson("person-1", arguments("person-1", "image/jpeg", 10)));

    assertEquals(List.of(), result.get("errors"));
  }

  @Test
  @DisplayName("a non-admin is refused for someone else, whether or not that person exists")
  void aNonAdminMayNotRequestAnUploadForSomeoneElse() {
    seed(dynamo, fullyPopulated("person-1", null));

    assertThrows(
        IllegalStateException.class,
        () -> invoke(asPerson("person-2", arguments("person-1", "image/png", 10))));
    assertThrows(
        IllegalStateException.class,
        () -> invoke(asPerson("person-2", arguments("no-such-person", "image/png", 10))));
  }

  @Test
  void rejectsUnauthenticatedRequests() {
    final Map<String, Object> event = asAdmin(arguments("person-1", "image/png", 10));
    event.remove("identity");

    assertThrows(IllegalStateException.class, () -> invoke(event));
  }

  @Test
  void rejectsAnUnsupportedContentType() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result = invoke(asAdmin(arguments("person-1", "image/webp", 10)));

    assertNull(result.get("upload"));
    assertEquals(List.of("UnsupportedContentType"), result.get("errors"));
  }

  @Test
  void rejectsADeclaredSizeAboveTheCeiling() {
    seed(dynamo, fullyPopulated("person-1", null));

    final Map<String, Object> result =
        invoke(asAdmin(arguments("person-1", "image/png", AvatarImage.MAX_UPLOAD_BYTES + 1)));

    assertNull(result.get("upload"));
    assertEquals(List.of("UploadTooLarge"), result.get("errors"));
  }

  @Test
  void rejectsANonPositiveDeclaredSize() {
    seed(dynamo, fullyPopulated("person-1", null));

    assertEquals(
        List.of("InvalidContentLength"),
        invoke(asAdmin(arguments("person-1", "image/png", 0))).get("errors"));
    assertEquals(
        List.of("InvalidContentLength"),
        invoke(asAdmin(arguments("person-1", "image/png", -5))).get("errors"));
  }

  @Test
  void rejectsAnUnknownPerson() {
    final Map<String, Object> result = invoke(asAdmin(arguments("ghost", "image/png", 10)));

    assertNull(result.get("upload"));
    assertEquals(List.of("PersonNotFound"), result.get("errors"));
  }
}
