package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/**
 * Unit — internal synthetic "live loading" generator, amps-denominated to match {@code
 * dynamic_line_rating}'s own unit (no real gridstatus.io call: the mock doesn't need real market
 * data to exercise der-control-api's real contract, and gridstatus.io's quota is a shared
 * ai-engineer resource, not backend-engineer's to spend unilaterally). AAA.
 */
class SyntheticLoadGeneratorTest {

  private final SyntheticLoadGenerator generator = new SyntheticLoadGenerator();

  @RepeatedTest(20)
  void currentLoadingAmpsIsAlwaysWithinConfiguredBounds() {
    // Act
    double loading = generator.currentLoadingAmps();

    // Assert
    assertThat(loading)
        .isGreaterThanOrEqualTo(SyntheticLoadGenerator.MIN_LOADING_AMPS)
        .isLessThanOrEqualTo(SyntheticLoadGenerator.MAX_LOADING_AMPS);
  }

  @Test
  void successiveReadingsVary() {
    // Act
    double first = generator.currentLoadingAmps();
    boolean sawADifferentValue = false;
    for (int i = 0; i < 50; i++) {
      if (generator.currentLoadingAmps() != first) {
        sawADifferentValue = true;
        break;
      }
    }

    // Assert: a generator that always returns the same number isn't simulating anything
    assertThat(sawADifferentValue).isTrue();
  }
}
