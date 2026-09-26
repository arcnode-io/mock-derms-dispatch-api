package io.arcnode.mockderms.dispatch;

/**
 * Real power on a three-phase feeder: {@code P = sqrt(3) x V_LL x I}, at unity power factor.
 *
 * <p>Shared by the curtailment setpoint and the operating envelope so the two cannot disagree about
 * what an amp is worth. Unity power factor is an assumption, not a measurement — nothing here
 * observes phase angle.
 */
public final class ThreePhasePower {

  private static final double SQRT_3 = Math.sqrt(3.0);
  private static final double VOLTS_PER_KV = 1000.0;

  private ThreePhasePower() {}

  /**
   * @param amps line current, signed
   * @param lineVoltageKv nominal line-to-line voltage, kV
   * @return real power in watts, carrying the sign of {@code amps}
   */
  public static double watts(double amps, double lineVoltageKv) {
    return SQRT_3 * lineVoltageKv * VOLTS_PER_KV * amps;
  }
}
