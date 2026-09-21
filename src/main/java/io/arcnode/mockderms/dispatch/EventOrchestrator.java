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
  private final SyntheticLoadGenerator loadGenerator;
  private final TriggerEvaluator triggerEvaluator;
  private final DerEventsClient client;
  private final Config config;
  private final Clock clock;

  private final AtomicReference<@Nullable ActiveEvent> activeEvent = new AtomicReference<>();
  private final AtomicInteger consecutiveRecoveryTicks = new AtomicInteger();

  private record ActiveEvent(
      String mrid, Instant dispatchedAt, DerEventRequest.Interval interval) {}

  public EventOrchestrator(
      DlrRatingSubscriber ratingSubscriber,
      SyntheticLoadGenerator loadGenerator,
      TriggerEvaluator triggerEvaluator,
      DerEventsClient client,
      Config config,
      Clock clock) {
    this.ratingSubscriber = ratingSubscriber;
    this.loadGenerator = loadGenerator;
    this.triggerEvaluator = triggerEvaluator;
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  @Scheduled(fixedDelay = 5000)
  public void tick() {
    Double ratingAmps = ratingSubscriber.currentRatingAmps();
    if (ratingAmps == null) {
      return;
    }
    double loadingAmps = loadGenerator.currentLoadingAmps();
    boolean triggering = triggerEvaluator.shouldTrigger(ratingAmps, loadingAmps);

    ActiveEvent current = activeEvent.get();
    if (current == null) {
      if (triggering) {
        dispatch(ratingAmps, loadingAmps);
      }
    } else {
      reassess(current, triggering);
    }
  }

  private void dispatch(double ratingAmps, double loadingAmps) {
    double excessAmps = loadingAmps - (ratingAmps - config.triggerMarginAmps());
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
    activeEvent.set(new ActiveEvent(mrid, now, interval));
    consecutiveRecoveryTicks.set(0);
    LOG.info(
        "dispatched mrid {} target {}W (excess {}A over margin)", mrid, targetWatts, excessAmps);
  }

  private void reassess(ActiveEvent current, boolean triggering) {
    if (maxDurationExceeded(current.dispatchedAt(), clock.instant())) {
      close(current);
      return;
    }
    if (triggering) {
      consecutiveRecoveryTicks.set(0);
      return;
    }
    if (consecutiveRecoveryTicks.incrementAndGet() >= RECOVERY_THRESHOLD_TICKS) {
      close(current);
    }
  }

  /** Package-visible for direct unit testing of the pure duration comparison. */
  boolean maxDurationExceeded(Instant dispatchedAt, Instant now) {
    long maxSeconds = (long) (config.maxEventDurationHours() * 3600);
    return Duration.between(dispatchedAt, now).toSeconds() >= maxSeconds;
  }

  private void close(ActiveEvent current) {
    // Reason: der-control-api has no literal "event_active=false" field — event_active is
    // derived, not settable. Closing for real means re-sending the same mrid with
    // eventStatus=CANCELLED, matching der-control-api's own retransmission-update behavior.
    client.dispatch(
        new DerEventRequest(
            current.mrid(),
            "CANCELLED",
            current.interval(),
            new DerEventRequest.ControlBase(null, null, null, null)));
    activeEvent.set(null);
    consecutiveRecoveryTicks.set(0);
    if (LOG.isInfoEnabled()) {
      LOG.info("closed mrid {}", current.mrid());
    }
  }
}
