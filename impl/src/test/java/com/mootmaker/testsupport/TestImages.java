package com.mootmaker.testsupport;

import module java.base;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Small generated images for avatar tests, so no binary fixtures need committing. */
public final class TestImages {

  private TestImages() {}

  /** An opaque image: left half red, right half blue, so a crop or a stretch is detectable. */
  public static byte[] png(final int width, final int height) {
    return encode(twoTone(width, height, BufferedImage.TYPE_INT_RGB), "png");
  }

  /** The same picture as {@link #png}, as a JPEG. */
  public static byte[] jpeg(final int width, final int height) {
    return encode(twoTone(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
  }

  /** Every pixel fully transparent. */
  public static byte[] transparentPng(final int width, final int height) {
    return encode(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png");
  }

  /** A format the JDK can decode but this API does not accept. */
  public static byte[] gif(final int width, final int height) {
    return encode(twoTone(width, height, BufferedImage.TYPE_INT_RGB), "gif");
  }

  public static BufferedImage decode(final byte[] bytes) {
    try {
      return ImageIO.read(new ByteArrayInputStream(bytes));
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static BufferedImage twoTone(final int width, final int height, final int type) {
    final BufferedImage image = new BufferedImage(width, height, type);
    final Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.RED);
      graphics.fillRect(0, 0, width / 2, height);
      graphics.setColor(Color.BLUE);
      graphics.fillRect(width / 2, 0, width - width / 2, height);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static byte[] encode(final BufferedImage image, final String format) {
    final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try {
      if (!ImageIO.write(image, format, bytes)) {
        throw new IllegalStateException("No ImageIO writer for " + format);
      }
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
  }
}
