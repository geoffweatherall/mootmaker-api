package com.mootmaker.avatar;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import module java.base;

import com.mootmaker.model.AvatarError;
import com.mootmaker.testsupport.TestImages;
import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AvatarImageTest {

  private static AvatarError rejectionOf(final byte[] source) {
    return assertThrows(AvatarRejected.class, () -> AvatarImage.normalise(source)).error();
  }

  private static String sha256Hex(final byte[] bytes) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** A PNG signature and IHDR chunk claiming the given size, with no image data behind it. */
  private static byte[] pngHeaderClaiming(final int width, final int height) {
    final ByteBuffer ihdr = ByteBuffer.allocate(17);
    ihdr.put("IHDR".getBytes(StandardCharsets.US_ASCII));
    ihdr.putInt(width).putInt(height);
    // 8-bit truecolour, default compression and filter, not interlaced.
    ihdr.put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
    final CRC32 crc = new CRC32();
    crc.update(ihdr.array());

    final ByteBuffer png = ByteBuffer.allocate(8 + 4 + 17 + 4);
    png.put(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
    png.putInt(13).put(ihdr.array()).putInt((int) crc.getValue());
    return png.array();
  }

  /** Splices an APP1 segment carrying {@code marker} in directly after a JPEG's SOI. */
  private static byte[] withExifSegment(final byte[] jpeg, final String marker) {
    final byte[] payload = ("Exif\0\0" + marker).getBytes(StandardCharsets.US_ASCII);
    final ByteBuffer out = ByteBuffer.allocate(jpeg.length + 4 + payload.length);
    out.put(jpeg, 0, 2);
    out.put((byte) 0xFF).put((byte) 0xE1).putShort((short) (payload.length + 2)).put(payload);
    out.put(jpeg, 2, jpeg.length - 2);
    return out.array();
  }

  private static boolean contains(final byte[] haystack, final String needle) {
    return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
  }

  // --- Normalisation -------------------------------------------------------------------

  @Test
  void normalisesAPngToASquareJpegOfTheCanonicalSize() {
    final AvatarImage.Normalised normalised = AvatarImage.normalise(TestImages.png(512, 512));

    assertEquals((byte) 0xFF, normalised.jpeg()[0], "JPEG starts with the SOI marker FF D8");
    assertEquals((byte) 0xD8, normalised.jpeg()[1]);
    final BufferedImage served = TestImages.decode(normalised.jpeg());
    assertEquals(AvatarImage.OUTPUT_SIZE, served.getWidth());
    assertEquals(AvatarImage.OUTPUT_SIZE, served.getHeight());
  }

  @Test
  void acceptsAJpeg() {
    final BufferedImage served =
        TestImages.decode(AvatarImage.normalise(TestImages.jpeg(300, 300)).jpeg());

    assertEquals(AvatarImage.OUTPUT_SIZE, served.getWidth());
  }

  @Test
  @DisplayName("a non-square image is centre-cropped, not stretched or letterboxed")
  void cropsANonSquareImageToItsCentre() {
    // 800x200, left half red and right half blue. Its central 200x200 square straddles the
    // boundary, so a centre crop is half red and half blue edge to edge. A stretch would give the
    // same split, so what distinguishes them is the far edge: letterboxing would leave white bars
    // top and bottom, and neither the crop nor the split may be lost.
    final BufferedImage served =
        TestImages.decode(AvatarImage.normalise(TestImages.png(800, 200)).jpeg());

    assertEquals(AvatarImage.OUTPUT_SIZE, served.getWidth());
    assertEquals(AvatarImage.OUTPUT_SIZE, served.getHeight());
    assertReddish(served, 20, 5);
    assertReddish(served, 20, 250);
    assertBluish(served, 236, 5);
    assertBluish(served, 236, 250);
  }

  @Test
  @DisplayName("transparency is flattened onto white, not left to come out black")
  void flattensTransparencyOntoWhite() {
    final BufferedImage served =
        TestImages.decode(AvatarImage.normalise(TestImages.transparentPng(128, 128)).jpeg());

    final Color centre = new Color(served.getRGB(128, 128));
    assertTrue(
        centre.getRed() > 240 && centre.getGreen() > 240 && centre.getBlue() > 240,
        "expected white, got " + centre);
  }

  // --- The key --------------------------------------------------------------------------

  @Test
  @DisplayName("the hash is of the bytes exactly as uploaded, not of the normalised output")
  void hashIsTheSha256OfTheSourceBytes() throws NoSuchAlgorithmException {
    final byte[] source = TestImages.png(128, 128);

    final AvatarImage.Normalised normalised = AvatarImage.normalise(source);

    assertEquals(sha256Hex(source), normalised.sourceSha256());
    assertFalse(
        sha256Hex(normalised.jpeg()).equals(normalised.sourceSha256()),
        "must not be the output's hash - demo-data can only reproduce the source's");
  }

  @Test
  void hashIsStableAcrossRunsAndDiffersBetweenImages() {
    final byte[] source = TestImages.png(128, 128);

    assertEquals(
        AvatarImage.normalise(source).sourceSha256(), AvatarImage.normalise(source).sourceSha256());
    assertFalse(
        AvatarImage.normalise(source)
            .sourceSha256()
            .equals(AvatarImage.normalise(TestImages.png(129, 129)).sourceSha256()));
  }

  // --- Sanitising -----------------------------------------------------------------------

  @Test
  @DisplayName("EXIF does not survive - the uploaded bytes are never what is served")
  void exifDoesNotSurviveReEncoding() {
    final String marker = "GPS-LATITUDE-MARKER-36.8485S";
    final byte[] source = withExifSegment(TestImages.jpeg(128, 128), marker);
    assertTrue(contains(source, marker), "the fixture must actually carry the marker");

    final AvatarImage.Normalised normalised = AvatarImage.normalise(source);

    assertFalse(contains(normalised.jpeg(), marker));
    assertFalse(contains(normalised.jpeg(), "Exif"));
  }

  // --- Rejections -----------------------------------------------------------------------

  @Test
  void rejectsBytesThatAreNotAnImage() {
    assertEquals(
        AvatarError.NotAnImage,
        rejectionOf("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));
    assertEquals(AvatarError.NotAnImage, rejectionOf(new byte[0]));
  }

  @Test
  @DisplayName("a format the JDK can decode but this API does not accept is still rejected")
  void rejectsAGif() {
    assertEquals(AvatarError.NotAnImage, rejectionOf(TestImages.gif(128, 128)));
  }

  @Test
  void rejectsAnImageThatStopsPartWayThrough() {
    final byte[] whole = TestImages.png(256, 256);

    assertEquals(AvatarError.NotAnImage, rejectionOf(Arrays.copyOf(whole, whole.length / 2)));
  }

  @Test
  void enforcesTheMinimumDimensionOnEitherSide() {
    assertEquals(AvatarError.ImageTooSmall, rejectionOf(TestImages.png(63, 200)));
    assertEquals(AvatarError.ImageTooSmall, rejectionOf(TestImages.png(200, 63)));
    assertDoesNotThrow(() -> AvatarImage.normalise(TestImages.png(64, 64)));
  }

  @Test
  void enforcesTheMaximumDimensionOnEitherSide() {
    assertEquals(AvatarError.ImageTooLarge, rejectionOf(pngHeaderClaiming(4097, 64)));
    assertEquals(AvatarError.ImageTooLarge, rejectionOf(pngHeaderClaiming(64, 4097)));
  }

  /**
   * The decompression bomb. Forty-one bytes claiming 60,000 x 60,000 pixels would need over ten
   * gigabytes to decode. If the size check ran after the decode rather than off the header, this
   * would be an OutOfMemoryError, or a NotAnImage from the missing image data - not ImageTooLarge.
   */
  @Test
  @DisplayName("an oversized image is rejected from its header, before anything is decoded")
  void rejectsADecompressionBombWithoutDecodingIt() {
    final byte[] bomb = pngHeaderClaiming(60_000, 60_000);
    assertTrue(bomb.length < 64, "the point is that the file itself is tiny");

    assertEquals(AvatarError.ImageTooLarge, rejectionOf(bomb));
  }

  // --- Declared upload ------------------------------------------------------------------

  @Test
  void acceptsADeclaredJpegOrPngUpToTheCeiling() {
    assertDoesNotThrow(() -> AvatarImage.validateDeclaredUpload("image/jpeg", 1));
    assertDoesNotThrow(
        () -> AvatarImage.validateDeclaredUpload("image/png", AvatarImage.MAX_UPLOAD_BYTES));
  }

  @Test
  void rejectsADeclaredTypeItCannotDecode() {
    for (final String contentType :
        Arrays.asList(
            "image/webp", "image/gif", "image/svg+xml", "text/html", "IMAGE/PNG", "", null)) {
      assertEquals(
          AvatarError.UnsupportedContentType,
          assertThrows(
                  AvatarRejected.class, () -> AvatarImage.validateDeclaredUpload(contentType, 100))
              .error(),
          String.valueOf(contentType));
    }
  }

  @Test
  void rejectsADeclaredSizeOutsideTheRange() {
    assertEquals(
        AvatarError.UploadTooLarge,
        assertThrows(
                AvatarRejected.class,
                () ->
                    AvatarImage.validateDeclaredUpload(
                        "image/png", AvatarImage.MAX_UPLOAD_BYTES + 1))
            .error());
    assertEquals(
        AvatarError.InvalidContentLength,
        assertThrows(AvatarRejected.class, () -> AvatarImage.validateDeclaredUpload("image/png", 0))
            .error());
    assertEquals(
        AvatarError.InvalidContentLength,
        assertThrows(
                AvatarRejected.class, () -> AvatarImage.validateDeclaredUpload("image/png", -1))
            .error());
  }

  private static void assertReddish(final BufferedImage image, final int x, final int y) {
    final Color color = new Color(image.getRGB(x, y));
    assertTrue(
        color.getRed() > 180 && color.getBlue() < 80,
        "expected red at " + x + "," + y + ", got " + color);
  }

  private static void assertBluish(final BufferedImage image, final int x, final int y) {
    final Color color = new Color(image.getRGB(x, y));
    assertTrue(
        color.getBlue() > 180 && color.getRed() < 80,
        "expected blue at " + x + "," + y + ", got " + color);
  }
}
