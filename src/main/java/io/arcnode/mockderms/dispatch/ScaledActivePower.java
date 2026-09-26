package io.arcnode.mockderms.dispatch;

/**
 * IEEE 2030.5 {@code ActivePower} — a signed {@code Int16} mantissa plus a power-of-ten multiplier,
 * both mandatory per sep.xsd. Real power therefore cannot travel as a plain watts number: anything
 * above 32767 W overflows the mantissa, so a site-scale setpoint has to be scaled.
 *
 * <p>Picks the smallest multiplier that fits, so precision is only lost once the magnitude actually
 * demands it.
 *
 * {@snippet : ScaledActivePower.ofWatts(1_500_000.0) // -> value 15000, multiplier 2 }
 */
public record ScaledActivePower(short value, byte multiplier) {

  // Reason: PowerOfTenMultiplierType's own documented range is -9..9, so 9 is the largest scale the
  // wire format can express.
  private static final byte MAX_MULTIPLIER = 9;

  /**
   * Encode watts into the spec's mantissa/multiplier pair.
   *
   * @param watts real power, signed — positive discharge, negative charge
   * @return the smallest-multiplier encoding that fits the mantissa
   * @throws IllegalArgumentException if the magnitude exceeds {@code Short.MAX_VALUE * 10^9}
   */
  public static ScaledActivePower ofWatts(double watts) {
    byte multiplier = 0;
    double scaled = watts;
    while (Math.abs(Math.round(scaled)) > Short.MAX_VALUE) {
      if (multiplier == MAX_MULTIPLIER) {
        throw new IllegalArgumentException(
            "%sW exceeds ActivePower's range at the spec's largest multiplier (%d)"
                .formatted(watts, MAX_MULTIPLIER));
      }
      multiplier++;
      scaled = watts / Math.pow(10, multiplier);
    }
    return new ScaledActivePower((short) Math.round(scaled), multiplier);
  }

  /** Decode back to watts, at the encoding's own resolution. */
  public double toWatts() {
    return value * Math.pow(10, multiplier);
  }
}
