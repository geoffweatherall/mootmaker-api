package com.mootmaker.dynamo;

/**
 * Layer 3 of the item-size guarantee fired: the real serialised day item exceeded the cap even
 * though validation, working from the modelled byte budget, let it through.
 *
 * <p>Reaching this means the byte model in {@code Limits} has drifted from reality - so it is a
 * signal worth logging loudly. It is not, however, an error the user should see as one: callers
 * turn it into the ordinary "this day is full" rejection, which is true, renderable, and does not
 * leak the fact that an internal estimate was wrong.
 */
public class DayItemTooLargeException extends RuntimeException {

  private final int actualBytes;
  private final int capBytes;

  public DayItemTooLargeException(final String date, final int actualBytes, final int capBytes) {
    super(
        "Day "
            + date
            + " serialises to "
            + actualBytes
            + " bytes, over the "
            + capBytes
            + "-byte cap. The modelled budget allowed it, so the byte model has drifted.");
    this.actualBytes = actualBytes;
    this.capBytes = capBytes;
  }

  public int actualBytes() {
    return actualBytes;
  }

  public int capBytes() {
    return capBytes;
  }
}
