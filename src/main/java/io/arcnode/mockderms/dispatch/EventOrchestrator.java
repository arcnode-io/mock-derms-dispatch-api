package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
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
  private static final double WATTS_PER_KV_AMP = 1000.0;
  // Reason: MVP placeholder, not spec'd by anyone — same debounce reasoning as
  // DeliveryShortfallMonitor's SHORTFALL_THRESHOLD_TICKS: avoid closing on a single noisy sample
  // ("rebound trip" per the sequence diagram's own wording).
  private static final int RECOVERY_THRESHOLD_TICKS = 3;

  private final DlrRatingSubscriber ratingSubscriber;
  private final LiveLoadingSubscriber loadingSubscriber;
  private final TriggerEvaluator triggerEvaluator;
  private final ZoneStressTracker zoneStressTracker;
  private final DerEventsClient client;
  private final Config config;
  private final Clock clock;

  private final AtomicReference<@Nullable ActiveEvent> activeEvent = new AtomicReference<>();
  private final AtomicInteger consecutiveRecoveryTicks = new AtomicInteger();

  private record ActiveEvent(
      String mrid, Instant dispatchedAt, DerEventRequest.Interval interval, double targetWatts) {}

  public EventOrchestrator(
      DlrRatingSubscriber ratingSubscriber,
      LiveLoadingSubscriber loadingSubscriber,
      TriggerEvaluator triggerEvaluator,
      ZoneStressTracker zoneStressTracker,
      DerEventsClient client,
      Config config,
      Clock clock) {
    this.ratingSubscriber = ratingSubscriber;
    this.loadingSubscriber = loadingSubscriber;
    this.triggerEvaluator = triggerEvaluator;
    this.zoneStressTracker = zoneStressTracker;
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  /**
   * The currently-commanded target active power (watts), or {@code null} when no event is active.
   * Read by the mirror package's own compliance comparison against the utility's real {@code
   * MirrorUsagePoint} report — this service already knows what it dispatched, so it doesn't need
   * that value repeated back to it, only the actual measured delivery.
   */
  public @Nullable Double currentTargetWatts() {
    ActiveEvent current = activeEvent.get();
    return current == null ? null : current.targetWatts();
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
    boolean triggering = triggerEvaluator.shouldTrigger(ratingAmps, loadingAmps, zoneStressed);

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
    double targetWatts = excessAmps * config.nominalLineVoltageKv() * WATTS_PER_KV_AMP;
    String mrid = UUID.randomUUID().toString();
    Instant now = clock.instant();
    long durationSeconds = (long) (config.maxEventDurationHours() * 3600);
    DerEventRequest.Interval interval = new DerEventRequest.Interval(now, durationSeconds);

    client.dispatch(
        new DerEventRequest(
            mrid,
            "ACTIVE",
            interval,
            new DerEventRequest.ControlBase(targetWatts, true, null, null)));
    activeEvent.set(new ActiveEvent(mrid, now, interval, targetWatts));
    consecutiveRecoveryTicks.set(0);
    LOG.info(
        "dispatched mrid {} target {}W (excess {}A over margin)", mrid, targetWatts, excessAmps);
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
            new DerEventRequest.ControlBase(null, null, null, null)));
    activeEvent.set(null);
    consecutiveRecoveryTicks.set(0);
    if (LOG.isInfoEnabled()) {
      LOG.info("closed mrid {}", current.mrid());
    }
  }
}
