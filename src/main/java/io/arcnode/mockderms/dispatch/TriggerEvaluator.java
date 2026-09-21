package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import org.springframework.stereotype.Component;

/**
 * Real-time trigger decision: has live loading eaten into the configured safety margin below the
 * current live rating? Both amps — the headroom curve/day-ahead forecast is a separate, planning-
 * facing concern; the actual dispatch decision uses live measurements only, not yesterday's
 * forecast.
 */
@Component
public class TriggerEvaluator {

  private final Config config;

  public TriggerEvaluator(Config config) {
    this.config = config;
  }

  /**
   * @param ratingAmps current live rating (dynamic_line_rating)
   * @param loadingAmps current live loading
   * @return true once loading has eaten into the margin, not merely reached it
   */
  public boolean shouldTrigger(double ratingAmps, double loadingAmps) {
    return loadingAmps > ratingAmps - config.triggerMarginAmps();
  }
}
