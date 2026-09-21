package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.mockderms.Config;
import org.junit.jupiter.api.Test;

/**
 * Unit — pure trigger decision: has live loading eaten into the safety margin below the current
 * live rating? Both amps, no unit conversion here — that happens only at magnitude computation.
 * AAA.
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
          13.8,
          50.0,
          4.0);
  private final TriggerEvaluator evaluator = new TriggerEvaluator(config);

  @Test
  void doesNotTriggerWhenLoadingIsWellBelowTheMargin() {
    // Arrange: rating 600A, margin 50A -> safe threshold is 550A, loading 400A is well clear
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 400.0)).isFalse();
  }

  @Test
  void triggersWhenLoadingExceedsTheMargin() {
    // Arrange: rating 600A, margin 50A -> loading 560A has eaten into the margin
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 560.0)).isTrue();
  }

  @Test
  void doesNotTriggerExactlyAtTheMarginBoundary() {
    // Arrange: loading exactly at rating - margin (550A) still leaves the full margin intact
    // Act / Assert
    assertThat(evaluator.shouldTrigger(600.0, 550.0)).isFalse();
  }
}
