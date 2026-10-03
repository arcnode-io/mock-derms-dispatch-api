package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Real-time monitoring + constraint dispatch + continuous reassessment + event close, tied together
 * on one tick. der_dispatch is a confirmed IEEE 2030.5/CSIP single-EndDevice-per-site concept
 * (verified via websearch), so there is never a "which DER" question — the target is always the
 * site's one der_dispatch, no enrollment registry.
 */
@Component
public class EventOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(EventOrchestrator.class);
  // Reason: MVP placeholder, not spec'd by anyone — same debounce reasoning as
  // DeliveryShortfallMonitor's SHORTFALL_THRESHOLD_TICKS: avoid closing on a single noisy sample
  // ("rebound trip" per the sequence diagram's own wording).
  private static final int RECOVERY_THRESHOLD_TICKS = 3;

  private final DlrRatingSubscriber ratingSubscriber;
  private final LiveLoadingSubscriber loadingSubscriber;
  private final TriggerEvaluator triggerEvaluator;
  private final ZoneStressTracker zoneStressTracker;
  private final EnvelopeDispatcher envelopeDispatcher;
  private final DerEventsClient client;
  private final Config config;
  private final Clock clock;

  private final AtomicReference<@Nullable ActiveEvent> activeEvent = new AtomicReference<>();
  private final AtomicInteger consecutiveRecoveryTicks = new AtomicInteger();

  private record ActiveEvent(
      String mrid,
      Instant dispatchedAt,
      DerEventRequest.Interval interval,
      double requiredReductionWatts) {}

  public EventOrchestrator(
      DlrRatingSubscriber ratingSubscriber,
      LiveLoadingSubscriber loadingSubscriber,
      TriggerEvaluator triggerEvaluator,
      ZoneStressTracker zoneStressTracker,
      EnvelopeDispatcher envelopeDispatcher,
      DerEventsClient client,
      Config config,
      Clock clock) {
    this.ratingSubscriber = ratingSubscriber;
    this.loadingSubscriber = loadingSubscriber;
    this.triggerEvaluator = triggerEvaluator;
    this.zoneStressTracker = zoneStressTracker;
    this.envelopeDispatcher = envelopeDispatcher;
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  /**
   * How much reduction the active event needs (watts), or {@code null} when no event is active.
   *
   * <p>This is a reduction, not a setpoint: it is the amount by which the conductor is over its
   * margin, and it is deliberately never sent as {@code opModTargetW}. Read by the mirror package's
   * compliance comparison against the utility's real {@code MirrorUsagePoint} report — this service
   * already knows what it asked for, so it needs only the measured delivery back.
   */
  public @Nullable Double currentRequiredReductionWatts() {
    ActiveEvent current = activeEvent.get();
    return current == null ? null : current.requiredReductionWatts();
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    Double ratingAmps = ratingSubscriber.currentRatingAmps();
    Double loadingAmpsBoxed = loadingSubscriber.currentLoadingAmps();
    if (ratingAmps == null || loadingAmpsBoxed == null) {
      return;
    }
    double loadingAmps = loadingAmpsBoxed;
    boolean zoneStressed = zoneStressTracker.isZoneStressed();
    double effectiveMargin = triggerEvaluator.effectiveMarginAmps(zoneStressed);
    // Reason: the envelope is continuous and independent of whether anything is triggering — it is
    // the boundary, not the command. It carries the same margin the trigger uses so the two agree.
    envelopeDispatcher.publish(ratingAmps, loadingAmps, effectiveMargin);
    boolean triggering = triggerEvaluator.shouldTrigger(ratingAmps, loadingAmps, zoneStressed);
    if (LOG.isInfoEnabled()) {
      String marginBreakdown;
      if (zoneStressed) {
        marginBreakdown =
            "%sA local + %sA ERCOT zone-stress boost"
                .formatted(config.triggerMarginAmps(), config.zoneStressMarginBoostAmps());
      } else if (zoneStressTracker.isZoneFeedStale()) {
        marginBreakdown = "local only, ERCOT zone feed stale";
      } else {
        marginBreakdown = "local only, ERCOT zone not stressed";
      }
      LOG.info(
          "📊 Evaluated: loading={}A vs rating={}A, margin={}A ({}) → {}",
          loadingAmps,
          ratingAmps,
          effectiveMargin,
          marginBreakdown,
          triggering ? "THRESHOLD TRIPPED" : "within margin");
    }

    ActiveEvent current = activeEvent.get();
    if (current == null) {
      if (triggering) {
        dispatch(ratingAmps, loadingAmps, zoneStressed);
      }
    } else {
      reassess(current, triggering);
    }
  }

  private void dispatch(double ratingAmps, double loadingAmps, boolean zoneStressed) {
    double excessAmps =
        loadingAmps - (ratingAmps - triggerEvaluator.effectiveMarginAmps(zoneStressed));
    // The reduction this utility needs, which is not the same thing as a setpoint for the site.
    double requiredReductionWatts =
        ThreePhasePower.watts(excessAmps, config.nominalLineVoltageKv());
    String mrid = Mrid.next();
    Instant now = clock.instant();
    long durationSeconds = (long) (config.maxEventDurationHours() * 3600);
    DerEventRequest.Interval interval = new DerEventRequest.Interval(now, durationSeconds);

    if (LOG.isInfoEnabled()) {
      LOG.info(
          "⚡ Sending event based on real-time analysis: reduction needed={}W",
          requiredReductionWatts);
    }
    client.dispatch(
        new DerEventRequest(
            mrid,
            "ACTIVE",
            interval,
            // Reason: no opModTargetW. The spec defines it as a target active power for the
            // DER, and a consumer writes it straight to the plant — but a line constraint is
            // not a setpoint, and this utility has no basis to compute one: it knows its
            // conductor, not how the site splits load between storage and compute. The
            // envelope (opModImpLimW, sent continuously by EnvelopeDispatcher) is the
            // constraint. Energize stays, since the site remains connected.
            new DerEventRequest.ControlBase(null, true, null, null)),
        now);
    activeEvent.set(new ActiveEvent(mrid, now, interval, requiredReductionWatts));
    consecutiveRecoveryTicks.set(0);
    LOG.info(
        "dispatched mrid {} needing {}W of reduction (excess {}A over margin)",
        mrid,
        requiredReductionWatts,
        excessAmps);
  }

  private void reassess(ActiveEvent current, boolean triggering) {
    if (maxDurationExceeded(current.dispatchedAt(), clock.instant())) {
      close(current, "COMPLETED");
      return;
    }
    if (triggering) {
      consecutiveRecoveryTicks.set(0);
      return;
    }
    if (consecutiveRecoveryTicks.incrementAndGet() >= RECOVERY_THRESHOLD_TICKS) {
      close(current, "CANCELLED");
    }
  }

  /** Package-visible for direct unit testing of the pure duration comparison. */
  boolean maxDurationExceeded(Instant dispatchedAt, Instant now) {
    long maxSeconds = (long) (config.maxEventDurationHours() * 3600);
    return Duration.between(dispatchedAt, now).toSeconds() >= maxSeconds;
  }

  private void close(ActiveEvent current, String eventStatus) {
    // Reason: der-control-api has no literal "event_active=false" field — event_active is
    // derived, not settable. Closing for real means re-sending the same mrid with a terminal
    // eventStatus, matching der-control-api's own retransmission-update behavior. COMPLETED (ran
    // its max Effective Scheduled Period) vs CANCELLED (cut short by sustained recovery) per IEEE
    // 2030.5-2023's own EventStatus.currentStatus distinction — not interchangeable labels.
    client.dispatch(
        new DerEventRequest(
            current.mrid(),
            eventStatus,
            current.interval(),
            new DerEventRequest.ControlBase(null, null, null, null)),
        clock.instant());
    activeEvent.set(null);
    consecutiveRecoveryTicks.set(0);
    if (LOG.isInfoEnabled()) {
      LOG.info("closed mrid {}", current.mrid());
    }
  }
}
