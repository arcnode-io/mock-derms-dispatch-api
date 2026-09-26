package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

/**
 * Unit — real power on a three-phase feeder is P = sqrt(3) x V_LL x I. Used by both the curtailment
 * setpoint and the operating envelope so the two cannot disagree about what an amp is worth. AAA.
 */
class ThreePhasePowerTest {

  private static final double NOMINAL_KV = 13.8;

  @Test
  void convertsAmpsToWattsAcrossThreePhases() {
    // Act
    double watts = ThreePhasePower.watts(100.0, NOMINAL_KV);

    // Assert: sqrt(3) x 13800 V x 100 A
    assertThat(watts).isCloseTo(2_390_230.11, within(0.01));
  }

  @Test
  void isNotMerelyVoltsTimesAmps() {
    // Arrange: the single-phase product, which understates a three-phase feeder by sqrt(3)
    double singlePhase = 13.8 * 1000.0 * 100.0;

    // Act
    double watts = ThreePhasePower.watts(100.0, NOMINAL_KV);

    // Assert
    assertThat(watts / singlePhase).isCloseTo(Math.sqrt(3.0), within(1.0e-9));
  }

  @Test
  void carriesTheSignOfTheCurrent() {
    // Act: negative amps mean the flow is the other way
    double watts = ThreePhasePower.watts(-100.0, NOMINAL_KV);

    // Assert
    assertThat(watts).isCloseTo(-2_390_230.11, within(0.01));
  }
}
