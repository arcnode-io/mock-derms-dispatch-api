package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import org.springframework.stereotype.Component;

/**
 * Real-time trigger decision: has live loading eaten into the safety margin below the current live
 * rating? Both amps — the headroom curve/day-ahead forecast is a separate, planning-facing concern;
 * the actual dispatch decision uses live measurements only, not yesterday's forecast.
 *
 * <p>The margin itself is not fixed — real-time North-zone ERCOT load ({@link ErcotZoneLoadClient})
 * tightens it when zone-wide stress is elevated, per POC-stage design (pending system-architect/SME
 * review): the local DLR trigger stays sole authority on whether to fire; the zone-level signal can
 * only make it more conservative, never independently cause a dispatch.
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
   * @param zoneLoadMw current North-zone load, MW ({@link ErcotZoneLoadClient})
   * @return true once loading has eaten into the effective margin, not merely reached it
   */
  public boolean shouldTrigger(double ratingAmps, double loadingAmps, double zoneLoadMw) {
    return loadingAmps > ratingAmps - effectiveMarginAmps(zoneLoadMw);
  }

  /** The margin actually in effect for the given zone-load reading — see class Javadoc. */
  public double effectiveMarginAmps(double zoneLoadMw) {
    double margin = config.triggerMarginAmps();
    if (zoneLoadMw > config.zoneStressThresholdMw()) {
      margin += config.zoneStressMarginBoostAmps();
    }
    return margin;
  }
}
