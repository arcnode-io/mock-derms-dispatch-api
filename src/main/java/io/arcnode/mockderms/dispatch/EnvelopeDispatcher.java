package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Sends the standing CSIP-AUS operating envelope — how much the site is allowed to import right
 * now. It is continuous, not triggered: the envelope is the boundary the site must stay inside at
 * all times, and it simply tightens as the line loads up. A curtailment event is the severe case on
 * top of it.
 *
 * <p>Carries envelope modes and no setpoint, which is what lets der-control-api keep it off
 * der_dispatch's channels — an envelope constrains, it commands nothing.
 *
 * <p>One long-lived mRID is reused so each send updates the same control in place, matching how a
 * real client re-reads one resource and sees new numbers. Minting a fresh mRID every tick would
 * instead accumulate a new persisted event every five seconds.
 */
@Component
public class EnvelopeDispatcher {

  // Reason: twice EventOrchestrator's 5s tick, so consecutive windows overlap and the envelope
  // never lapses in the gap between updates.
  private static final long WINDOW_SECONDS = 10L;
  // Reason: the site does not export. CSIP-AUS types this as an ActivePower like any other, so zero
  // is stated explicitly rather than left absent, which would mean "no export constraint given".
  private static final double EXPORT_LIMIT_WATTS = 0.0;

  private final DerEventsClient client;
  private final Config config;
  private final Clock clock;
  private final String mrid = Mrid.next();

  public EnvelopeDispatcher(DerEventsClient client, Config config, Clock clock) {
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  /**
   * @param ratingAmps the line's live dynamic rating
   * @param loadingAmps live line loading. Until site-import coupling lands this is treated as other
   *     customers' load, so the site's own import is not subtracted out of it first.
   * @param effectiveMarginAmps the margin the trigger is currently working to, including any ERCOT
   *     zone-stress boost. The published ceiling has to reflect the same conservatism the trigger
   *     enforces, or a site obeying the envelope exactly would still be curtailed for doing so.
   */
  public void publish(double ratingAmps, double loadingAmps, double effectiveMarginAmps) {
    if (!config.envelopeScheduleEnabled()) {
      return;
    }
    double headroomAmps = Math.max(0.0, ratingAmps - effectiveMarginAmps - loadingAmps);
    double importLimitWatts = ThreePhasePower.watts(headroomAmps, config.nominalLineVoltageKv());
    Instant now = clock.instant();
    client.dispatch(
        new DerEventRequest(
            mrid,
            "ACTIVE",
            new DerEventRequest.Interval(now, WINDOW_SECONDS),
            new DerEventRequest.ControlBase(null, null, importLimitWatts, EXPORT_LIMIT_WATTS)),
        now);
  }
}
