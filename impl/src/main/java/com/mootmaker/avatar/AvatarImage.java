package com.mootmaker.avatar;

import module java.base;

import com.mootmaker.model.AvatarError;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Validates and normalises an uploaded avatar, and is the only thing in this system that decides
 * what an avatar may be.
 *
 * <p><b>Re-encoding is the sanitiser, and the reason this class exists at all.</b> Uploaded bytes
 * are never served. Decoding to pixels and encoding a fresh JPEG neutralises polyglot files - a
 * payload that is simultaneously a valid image and a valid script survives a content-type check but
 * not a round trip through a decoder - and strips EXIF, which will carry GPS coordinates and device
 * identifiers the moment real users upload photographs rather than demo data.
 *
 * <p><b>Dimensions are read from the header before anything is decoded.</b> {@link
 * javax.imageio.ImageReader#read} allocates for the <i>decoded</i> size, so a few hundred bytes of
 * PNG can legitimately demand gigabytes of heap - the decompression bomb. The upload ceiling does
 * not protect against this at all, because the ceiling is on the compressed bytes and the
 * allocation is driven by the width and height in the header. {@link
 * javax.imageio.ImageReader#getWidth} reads that header without decoding, so the rejection happens
 * before the allocation rather than after it.
 *
 * <p>One canonical derivative: {@value #OUTPUT_SIZE}x{@value #OUTPUT_SIZE} JPEG, comfortably above
 * twice the largest size any client renders. There is no resizing on read and no format
 * negotiation; see the design's explicit non-goals.
 */
public final class AvatarImage {

  /** The largest upload accepted, applied to the size the caller declares up front. */
  public static final long MAX_UPLOAD_BYTES = 2L * 1024 * 1024;

  /** Below this, there is not enough detail for the normalised output to be worth serving. */
  public static final int MIN_SOURCE_DIMENSION = 64;

  /** Above this, decoding is refused outright - see this class's note on decompression bombs. */
  public static final int MAX_SOURCE_DIMENSION = 4096;

  /**
   * The edge length of every served avatar. Square, because every client renders it in a circle.
   */
  public static final int OUTPUT_SIZE = 256;

  /** What every served avatar is, regardless of what was uploaded. */
  public static final String OUTPUT_CONTENT_TYPE = "image/jpeg";

  /**
   * Both are decoded by {@code javax.imageio} in the JDK itself. WebP would need a third-party
   * decoder, and DiceBear's CLI renders PNG, so nothing this project produces needs one.
   */
  public static final Set<String> ACCEPTED_CONTENT_TYPES = Set.of("image/jpeg", "image/png");

  /**
   * Set explicitly rather than left to the JDK's default, which is unspecified and has differed
   * between versions. Output bytes need not be reproducible - the key holds the hash of the
   * <i>source</i>, not the output, precisely so that a decoder or encoder upgrade cannot invalidate
   * an existing URL - but an encoder whose quality silently changes under us is still worth
   * pinning.
   */
  private static final float OUTPUT_QUALITY = 0.85f;

  /** What {@code ImageReader.getFormatName()} reports for the two types accepted above. */
  private static final Set<String> ACCEPTED_FORMAT_NAMES = Set.of("jpeg", "jpg", "png");

  static {
    // Lambda has no display, and AWT will try to open one without this. Set here rather than as a
    // JVM flag so that a unit run and a deployed run take the same path.
    System.setProperty("java.awt.headless", "true");
    // ImageIO otherwise stages through a temporary file on disk. Everything here is at most a
    // couple of megabytes and already in memory, and Lambda's /tmp is shared across invocations
    // on the same execution environment.
    ImageIO.setUseCache(false);
  }

  private AvatarImage() {}

  /**
   * A normalised avatar: the bytes to serve, and the hash of the bytes that produced them.
   *
   * <p>The hash is of the <b>source</b>, deliberately. It lets mootmaker-demo-data recognise which
   * of its bundled images are already in use by hashing its own files, with no dependence on this
   * class producing byte-for-byte identical output across a JDK upgrade.
   *
   * <p>Holds an array, so the generated {@code equals} and {@code hashCode} compare by identity
   * rather than by content. Nothing compares these, and nothing should.
   */
  public record Normalised(byte[] jpeg, String sourceSha256) {}

  /**
   * Checks what the caller claims about an upload it has not made yet, before a presigned URL is
   * issued. Cheap, and the only rejection possible before any bytes move.
   *
   * <p>Passing this proves nothing about the bytes. The content type is client-asserted, and S3
   * enforces only that the uploaded request's header matches the one that was signed - never that
   * the bytes match the header. Only {@link #normalise} decoding them proves that.
   *
   * @throws AvatarRejected if the declared type is unsupported, or the declared size is
   *     non-positive or above {@link #MAX_UPLOAD_BYTES}
   */
  public static void validateDeclaredUpload(final String contentType, final long contentLength) {
    if (contentType == null || !ACCEPTED_CONTENT_TYPES.contains(contentType)) {
      throw new AvatarRejected(AvatarError.UnsupportedContentType);
    }
    if (contentLength <= 0) {
      throw new AvatarRejected(AvatarError.InvalidContentLength);
    }
    if (contentLength > MAX_UPLOAD_BYTES) {
      throw new AvatarRejected(AvatarError.UploadTooLarge);
    }
  }

  /**
   * Decodes, centre-crops, scales and re-encodes an uploaded image.
   *
   * @throws AvatarRejected if the bytes are not a supported image, or its dimensions are outside
   *     the accepted range
   */
  public static Normalised normalise(final byte[] source) {
    // Hashed before anything else, and from the bytes exactly as uploaded - the value has to be a
    // pure function of what the client sent for demo-data's read-back to work.
    final String sourceSha256 = sha256Hex(source);
    return new Normalised(encodeJpeg(squareThumbnail(decode(source))), sourceSha256);
  }

  private static BufferedImage decode(final byte[] source) {
    try (ImageInputStream input =
        ImageIO.createImageInputStream(new ByteArrayInputStream(source))) {
      if (input == null) {
        throw new AvatarRejected(AvatarError.NotAnImage);
      }
      final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
      if (!readers.hasNext()) {
        throw new AvatarRejected(AvatarError.NotAnImage);
      }
      final ImageReader reader = readers.next();
      try {
        reader.setInput(input);
        // A reader was found, but it may be for a format this API does not accept - the JDK ships
        // BMP, GIF and WBMP readers too, and none of them is in ACCEPTED_CONTENT_TYPES.
        if (!ACCEPTED_FORMAT_NAMES.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
          throw new AvatarRejected(AvatarError.NotAnImage);
        }
        // Header only. See this class's note on decompression bombs for why the order matters.
        final int width = reader.getWidth(0);
        final int height = reader.getHeight(0);
        if (width > MAX_SOURCE_DIMENSION || height > MAX_SOURCE_DIMENSION) {
          throw new AvatarRejected(AvatarError.ImageTooLarge);
        }
        if (width < MIN_SOURCE_DIMENSION || height < MIN_SOURCE_DIMENSION) {
          throw new AvatarRejected(AvatarError.ImageTooSmall);
        }
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    } catch (final IOException e) {
      // Truncated, corrupt, or a file whose header promised more than it delivered. All of them
      // are the caller's problem to fix and none of them is a fault of this service.
      throw new AvatarRejected(AvatarError.NotAnImage);
    }
  }

  /**
   * Centre-crops to a square and scales to {@link #OUTPUT_SIZE}, onto an opaque background.
   *
   * <p>Cropping rather than letterboxing or stretching: a client renders this inside a circular
   * mask, so the corners are never visible and a distorted face would be. Opaque because JPEG has
   * no alpha channel - a transparent PNG composited onto nothing would come out black.
   */
  private static BufferedImage squareThumbnail(final BufferedImage source) {
    final int edge = Math.min(source.getWidth(), source.getHeight());
    final int left = (source.getWidth() - edge) / 2;
    final int top = (source.getHeight() - edge) / 2;

    final BufferedImage output =
        new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_RGB);
    final Graphics2D graphics = output.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE);
      graphics.drawImage(
          source, 0, 0, OUTPUT_SIZE, OUTPUT_SIZE, left, top, left + edge, top + edge, null);
    } finally {
      graphics.dispose();
    }
    return output;
  }

  private static byte[] encodeJpeg(final BufferedImage image) {
    final Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      // Not a caller error: every JDK ships a JPEG writer, so this is a broken runtime.
      throw new IllegalStateException("No JPEG writer is registered in this JVM");
    }
    final ImageWriter writer = writers.next();
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (MemoryCacheImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
      writer.setOutput(output);
      final ImageWriteParam parameters = writer.getDefaultWriteParam();
      parameters.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      parameters.setCompressionQuality(OUTPUT_QUALITY);
      writer.write(null, new IIOImage(image, null, null), parameters);
    } catch (final IOException e) {
      // Writing to a byte array, so there is no I/O here that can legitimately fail.
      throw new IllegalStateException("Failed to encode a decoded avatar as JPEG", e);
    } finally {
      writer.dispose();
    }
    return bytes.toByteArray();
  }

  private static String sha256Hex(final byte[] source) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source));
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is required of every JVM", e);
    }
  }
}
