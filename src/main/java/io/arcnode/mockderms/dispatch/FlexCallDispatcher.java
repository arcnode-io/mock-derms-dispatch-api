package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The contracted flex call: the utility exercising the curtailment it bought the right to call.
 *
 * <p>Separate from {@link EventOrchestrator} because it is a different utility behaviour, not a
 * variant of one. A line constraint is a boundary — the conductor is near its ampacity and the
 * utility knows nothing about how the site splits load, so it sends an envelope and no setpoint.
 * This is the opposite: a program the site is enrolled in, with a contracted depth, so the utility
 * knows exactly how much it is entitled to ask for and sends it as {@code opModTargetW}. The two
 * can be in force at once, and der-control-api already resolves concurrent open events.
 *
 * <p>Deliberately carries no {@code opModImpLimW}/{@code opModExpLimW}: der-control-api classifies
 * a target-less event that carries a limit as envelope-only and never publishes {@code
 * event_active} or {@code target_setpoint_present} for it. A limit here would put this straight
 * back on the envelope path it exists to exercise.
 *
 * <p>The depth and the interval are contract terms, not tuning knobs. {@code flexMinIntervalHours}
 * in particular is the term the site's recharge rate was sized against (edp-api derives {@code
 * bess_recharge_mw} as the rate that refills the flex energy inside it), so calling again sooner
 * would ask for energy the plant was never built to have refilled.
 */
@Component
public class FlexCallDispatcher {

  private static final Logger LOG = LoggerFactory.getLogger(FlexCallDispatcher.class);
  private static final double WATTS_PER_KW = 1000.0;
  private static final double PERCENT = 100.0;

  private final ZoneStressTracker zoneStressTracker;
  private final DerEventsClient client;
  private final Config config;
  private final Clock clock;

  private final AtomicReference<@Nullable OpenCall> openCall = new AtomicReference<>();
  private final AtomicReference<@Nullable Instant> lastCallEndedAt = new AtomicReference<>();

  private record OpenCall(String mrid, Instant calledAt, DerEventRequest.Interval interval) {}

  public FlexCallDispatcher(
      ZoneStressTracker zoneStressTracker, DerEventsClient client, Config config, Clock clock) {
    this.zoneStressTracker = zoneStressTracker;
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  /**
   * Shares {@link EventOrchestrator}'s cadence: both are the same utility reassessing, and a
   * program call that outran its own contracted duration by minutes would be the utility in breach
   * of its own terms.
   */
  @Scheduled(fixedDelay = 5000)
  public void tick() {
    OpenCall current = openCall.get();
    if (current != null) {
      if (durationElapsed(current.calledAt())) {
        close(current);
      }
      return;
    }
    if (zoneStressTracker.isZoneStressed() && !insideRecoveryInterval()) {
      call();
    }
  }

  /**
   * The watts this utility is entitled to ask the site for — positive, since the site discharges.
   */
  private double contractedTargetWatts() {
    return config.flexDepthPercent() / PERCENT * config.flexEnrolledPeakKw() * WATTS_PER_KW;
  }

  private void call() {
    Instant now = clock.instant();
    long durationSeconds = (long) (config.flexMaxDurationHours() * 3600);
    DerEventRequest.Interval interval = new DerEventRequest.Interval(now, durationSeconds);
    String mrid = Mrid.next();
    double targetWatts = contractedTargetWatts();
    client.dispatch(
        new DerEventRequest(
            mrid,
            "ACTIVE",
            interval,
            // Energize stays true: a flex call curtails, it does not disconnect the site.
            new DerEventRequest.ControlBase(targetWatts, true, null, null)),
        now,
        DerPrograms.FLEX);
    openCall.set(new OpenCall(mrid, now, interval));
    if (LOG.isInfoEnabled()) {
      LOG.info(
          "⚡ Flex call {}: commanding {}W, {}% of the enrolled {}kW, for up to {}h",
          mrid,
          targetWatts,
          config.flexDepthPercent(),
          config.flexEnrolledPeakKw(),
          config.flexMaxDurationHours());
    }
  }

  private void close(OpenCall current) {
    // Reason: same close shape as EventOrchestrator — der-control-api derives event_active rather
    // than exposing it, so the only way to release the site is to re-send the mRID terminal.
    // COMPLETED, never CANCELLED: a flex call ends because it ran its contracted duration, which
    // is the event completing, not the utility cutting it short.
    client.dispatch(
        new DerEventRequest(
            current.mrid(),
            "COMPLETED",
            current.interval(),
            new DerEventRequest.ControlBase(null, null, null, null)),
        clock.instant(),
        DerPrograms.FLEX);
    openCall.set(null);
    lastCallEndedAt.set(clock.instant());
    if (LOG.isInfoEnabled()) {
      LOG.info("🔚 Flex call {} completed its contracted duration", current.mrid());
    }
  }

  private boolean durationElapsed(Instant calledAt) {
    long maxSeconds = (long) (config.flexMaxDurationHours() * 3600);
    return Duration.between(calledAt, clock.instant()).toSeconds() >= maxSeconds;
  }

  private boolean insideRecoveryInterval() {
    Instant endedAt = lastCallEndedAt.get();
    if (endedAt == null) {
      return false;
    }
    long elapsed = Duration.between(endedAt, clock.instant()).toSeconds();
    return elapsed < (long) (config.flexMinIntervalHours() * 3600);
  }
}
