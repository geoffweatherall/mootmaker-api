package com.mootmaker.avatar;

import com.mootmaker.model.AvatarError;

/**
 * Carries a validation failure out of image decoding, mirroring {@code MeetingRejected}.
 *
 * <p>An exception rather than a return value for the same reason that one is: the checks are spread
 * through a sequence that otherwise produces an image, and several of them sit inside code that has
 * to return a {@code BufferedImage} or nothing at all. Throwing both reports the failure and
 * abandons the work.
 *
 * <p>These are validation results, not faults. Handlers turn them into the typed {@code errors}
 * array the schema promises - a client renders them, and none of them is ever a 500. That
 * distinction is the whole point of the design's requirement that a rejected upload return a
 * structured error rather than a stack trace.
 *
 * <p>Built with writable stack traces and suppression disabled, like {@code MeetingRejected}: these
 * are thrown on ordinary input and never read as a stack trace.
 */
public final class AvatarRejected extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final AvatarError error;

  public AvatarRejected(final AvatarError error) {
    super(error.name(), null, false, false);
    this.error = error;
  }

  public AvatarError error() {
    return error;
  }
}
