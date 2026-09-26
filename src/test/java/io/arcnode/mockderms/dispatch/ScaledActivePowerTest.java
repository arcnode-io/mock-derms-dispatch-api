package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit — IEEE 2030.5 encodes real power as ActivePower{value:Int16, multiplier:Int8}, so a plain
 * watts number above 32767 cannot be carried at all; it has to be split into a mantissa and a
 * power-of-ten multiplier. Picks the smallest multiplier that fits, so precision is only ever lost
 * when the magnitude genuinely requires it. AAA.
 */
class ScaledActivePowerTest {

  @Test
  void scalesUpUntilTheMantissaFitsInInt16() {
    // Arrange: 1.5 MW — the real dispatch magnitude, and ~46x too large for an Int16
    double watts = 1_500_000.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) 15_000);
    assertThat(scaled.multiplier()).isEqualTo((byte) 2);
  }

  @Test
  void usesNoMultiplierWhenTheValueAlreadyFits() {
    // Arrange
    double watts = 100.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) 100);
    assertThat(scaled.multiplier()).isEqualTo((byte) 0);
  }

  @Test
  void keepsTheSignWhenScalingACurtailmentSetpoint() {
    // Arrange: negative is charge, per bess_rack's own active_power convention
    double watts = -1_500_000.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) -15_000);
    assertThat(scaled.multiplier()).isEqualTo((byte) 2);
  }

  @Test
  void treatsTheInt16BoundaryAsStillFitting() {
    // Arrange: Short.MAX_VALUE exactly — no scaling needed
    double watts = 32_767.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) 32_767);
    assertThat(scaled.multiplier()).isEqualTo((byte) 0);
  }

  @Test
  void quantizesOnePastTheBoundaryRatherThanOverflowing() {
    // Arrange: one watt past Int16 — must scale, and the encoding's own resolution rounds it
    double watts = 32_768.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) 3_277);
    assertThat(scaled.multiplier()).isEqualTo((byte) 1);
    assertThat(scaled.toWatts()).isEqualTo(32_770.0);
  }

  @Test
  void encodesZeroWithoutScaling() {
    // Arrange
    double watts = 0.0;

    // Act
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);

    // Assert
    assertThat(scaled.value()).isEqualTo((short) 0);
    assertThat(scaled.multiplier()).isEqualTo((byte) 0);
  }

  @Test
  void roundTripsARealDispatchMagnitudeExactly() {
    // Arrange
    double watts = 1_500_000.0;

    // Act
    double roundTripped = ScaledActivePower.ofWatts(watts).toWatts();

    // Assert
    assertThat(roundTripped).isEqualTo(watts);
  }

  @Test
  void rejectsAMagnitudeBeyondTheSpecsLargestMultiplier() {
    // Arrange: PowerOfTenMultiplierType tops out at 9, so Short.MAX_VALUE * 10^9 is the ceiling
    double watts = 1.0e15;

    // Act / Assert
    assertThatThrownBy(() -> ScaledActivePower.ofWatts(watts))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("multiplier");
  }
}
