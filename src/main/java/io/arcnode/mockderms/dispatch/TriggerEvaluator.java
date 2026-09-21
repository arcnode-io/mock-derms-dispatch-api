package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import org.springframework.stereotype.Component;

/**
 * Real-time trigger decision: has live loading eaten into the safety margin below the current live
 * rating? Both amps — the headroom curve/day-ahead forecast is a separate, planning-facing concern;
 * the actual dispatch decision uses live measurements only, not yesterday's forecast.
 *
 * <p>The margin itself is not fixed — {@link ZoneStressTracker}'s debounced real-time North-zone
 * ERCOT signal tightens it when zone-wide stress is sustained. SME-reviewed and approved: the local
 * DLR trigger stays sole authority on whether to fire; the zone-level signal can only make it more
 * conservative, never independently cause a dispatch. {@code zoneStressMarginBoostAmps} itself is
 * arbitrary, not a placeholder awaiting review — see {@link io.arcnode.mockderms.Config}'s javadoc.
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
   * @param zoneStressed debounced North-zone stress signal ({@link ZoneStressTracker})
   * @return true once loading has eaten into the effective margin, not merely reached it
   */
  public boolean shouldTrigger(double ratingAmps, double loadingAmps, boolean zoneStressed) {
    return loadingAmps > ratingAmps - effectiveMarginAmps(zoneStressed);
  }

  /** The margin actually in effect given the current zone-stress signal — see class Javadoc. */
  public double effectiveMarginAmps(boolean zoneStressed) {
    double margin = config.triggerMarginAmps();
    if (zoneStressed) {
      margin += config.zoneStressMarginBoostAmps();
    }
    return margin;
  }
}
