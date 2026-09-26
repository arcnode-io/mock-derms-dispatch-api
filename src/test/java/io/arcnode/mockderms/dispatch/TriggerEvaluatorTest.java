package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.mockderms.Config;
import org.junit.jupiter.api.Test;

/**
 * Unit — pure trigger decision: has live loading eaten into the safety margin below the current
 * live rating? Both amps, no unit conversion here — that happens only at magnitude computation. The
 * local trigger stays sole authority on whether to fire; the debounced North-zone stress signal
 * ({@link ZoneStressTracker}) can only tighten (increase) the margin, never independently cause a
 * trigger. AAA.
 */
class TriggerEvaluatorTest {

  private final Config config =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "tcp://localhost:1883",
          "arcnode_mock_derms_dispatch_api",
          "site_001",
          "http://localhost:8080",
          "http://localhost:8081",
          13.8,
          50.0,
          4.0,
          "dlr_rtu_demo",
          "https://example.invalid/token",
          "https://example.invalid/archive",
          1800.0,
          25.0);
  private final TriggerEvaluator evaluator = new TriggerEvaluator(config);

  @Test
  void doesNotTriggerWhenLoadingIsWellBelowTheMargin() {
    // Arrange: rating 600A, margin 50A -> safe threshold is 550A, loading 400A is well clear
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 400.0, false)).isFalse();
  }

  @Test
  void triggersWhenLoadingExceedsTheMargin() {
    // Arrange: rating 600A, margin 50A -> loading 560A has eaten into the margin
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 560.0, false)).isTrue();
  }

  @Test
  void doesNotTriggerExactlyAtTheMarginBoundary() {
    // Arrange: loading exactly at rating - margin (550A) still leaves the full margin intact
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 550.0, false)).isFalse();
  }

  @Test
  void baseMarginAppliesWhenZoneIsNotStressed() {
    // Act / Assert
    assertThat(evaluator.effectiveMarginAmps(false)).isEqualTo(50.0);
  }

  @Test
  void boostedMarginAppliesWhenZoneIsStressed() {
    // Act / Assert: base 50A + boost 25A
    assertThat(evaluator.effectiveMarginAmps(true)).isEqualTo(75.0);
  }

  @Test
  void zoneStressCanTipALoadingThatWouldOtherwiseNotTrigger() {
    // Arrange: rating 600A, loading 540A -> under the base 50A margin (threshold 550A) this is
    // safe, but under the boosted 75A margin (threshold 525A) it has eaten into the margin
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 540.0, false)).isFalse();
    assertThat(evaluator.shouldTrigger(600.0, 540.0, true)).isTrue();
  }
}
