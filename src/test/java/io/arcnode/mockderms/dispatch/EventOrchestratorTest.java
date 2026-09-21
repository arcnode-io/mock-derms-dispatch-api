package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — ties rating/loading/trigger into dispatch, and tracks the active event through to close.
 * Mocked collaborators, AAA.
 */
@ExtendWith(MockitoExtension.class)
class EventOrchestratorTest {

  private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");
  // Reason: TriggerEvaluator is mocked here, so its zone-stress margin logic isn't under test —
  // only that EventOrchestrator passes the debounced zone-stress signal through. Value arbitrary.
  private static final boolean ZONE_STRESSED = false;

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
          4.0,
          "dlr_rtu_demo",
          "https://example.invalid/token",
          "https://example.invalid/archive",
          1800.0,
          25.0);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private DlrRatingSubscriber ratingSubscriber;
  @Mock private SyntheticLoadGenerator loadGenerator;
  @Mock private TriggerEvaluator triggerEvaluator;
  @Mock private ZoneStressTracker zoneStressTracker;
  @Mock private DerEventsClient client;

  private EventOrchestrator orchestrator() {
    return new EventOrchestrator(
        ratingSubscriber,
        loadGenerator,
        triggerEvaluator,
        zoneStressTracker,
        client,
        config,
        clock);
  }

  @Test
  void doesNothingWhenNoRatingHasArrivedYet() {
    // Arrange
    given(ratingSubscriber.currentRatingAmps()).willReturn(null);

    // Act
    orchestrator().tick();

    // Assert
    verify(client, never()).dispatch(any());
  }

  @Test
  void doesNothingWhenNotTriggering() {
    // Arrange
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(400.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 400.0, ZONE_STRESSED)).willReturn(false);

    // Act
    orchestrator().tick();

    // Assert
    verify(client, never()).dispatch(any());
  }

  @Test
  void dispatchesWithPositiveTargetActivePowerWhenTriggering() {
    // Arrange: rating 600A, effective margin 50A, loading 560A -> 10A over threshold
    // 10A * 13.8kV * 1000 = 138,000W
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(560.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 560.0, ZONE_STRESSED)).willReturn(true);
    given(triggerEvaluator.effectiveMarginAmps(ZONE_STRESSED)).willReturn(50.0);

    // Act
    orchestrator().tick();

    // Assert
    ArgumentCaptor<DerEventRequest> request = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client).dispatch(request.capture());
    assertThat(request.getValue().eventStatus()).isEqualTo("ACTIVE");
    assertThat(request.getValue().derControlBase().opModTargetW()).isEqualTo(138_000.0);
    assertThat(request.getValue().derControlBase().opModEnergize()).isTrue();
  }

  @Test
  void doesNotRedispatchOnEveryTickWhileAlreadyActive() {
    // Arrange
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(560.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 560.0, ZONE_STRESSED)).willReturn(true);
    given(triggerEvaluator.effectiveMarginAmps(ZONE_STRESSED)).willReturn(50.0);
    EventOrchestrator orchestrator = orchestrator();

    // Act
    orchestrator.tick();
    orchestrator.tick();
    orchestrator.tick();

    // Assert
    verify(client, times(1)).dispatch(any());
  }

  @Test
  void closesAfterSustainedRecoveryNotOnASingleSample() {
    // Arrange: trigger, then recover
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(560.0, 400.0, 400.0, 400.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 560.0, ZONE_STRESSED)).willReturn(true);
    given(triggerEvaluator.shouldTrigger(600.0, 400.0, ZONE_STRESSED)).willReturn(false);
    given(triggerEvaluator.effectiveMarginAmps(ZONE_STRESSED)).willReturn(50.0);
    EventOrchestrator orchestrator = orchestrator();
    orchestrator.tick(); // dispatches

    // Act: two recovered samples — not sustained yet
    orchestrator.tick();
    orchestrator.tick();
    verify(client, times(1)).dispatch(any());

    // Act: third consecutive recovered sample — sustained
    orchestrator.tick();

    // Assert
    ArgumentCaptor<DerEventRequest> request = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client, times(2)).dispatch(request.capture());
    DerEventRequest close = request.getAllValues().get(1);
    assertThat(close.eventStatus()).isEqualTo("CANCELLED");
    assertThat(close.mrid()).isEqualTo(request.getAllValues().get(0).mrid());
  }

  @Test
  void aSingleRecoveredSampleDoesNotResetAfterGoingBackToTriggering() {
    // Arrange: trigger, one recovered sample (not sustained), back to triggering — must not close
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(560.0, 400.0, 560.0, 400.0, 400.0, 400.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 560.0, ZONE_STRESSED)).willReturn(true);
    given(triggerEvaluator.shouldTrigger(600.0, 400.0, ZONE_STRESSED)).willReturn(false);
    given(triggerEvaluator.effectiveMarginAmps(ZONE_STRESSED)).willReturn(50.0);
    EventOrchestrator orchestrator = orchestrator();

    // Act: dispatch, one recovery tick, back to triggering (should reset the recovery counter),
    // then three more recovered ticks (needed again in full, since the counter reset)
    orchestrator.tick(); // dispatch
    orchestrator.tick(); // recovered (1/3)
    orchestrator.tick(); // triggering again -> resets
    orchestrator.tick(); // recovered (1/3)
    orchestrator.tick(); // recovered (2/3)
    verify(client, times(1)).dispatch(any());
    orchestrator.tick(); // recovered (3/3) -> closes

    // Assert
    verify(client, times(2)).dispatch(any());
  }

  /** Advanceable fake — lets one orchestrator instance see time pass across ticks. */
  private static final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant now) {
      this.now = now;
    }

    void advance(java.time.Duration by) {
      now = now.plus(by);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  @Test
  void closesOnMaxDurationEvenWhileStillTriggering() {
    // Arrange: still over the margin the whole time, but the event has been open past
    // maxEventDurationHours (4h)
    given(ratingSubscriber.currentRatingAmps()).willReturn(600.0);
    given(loadGenerator.currentLoadingAmps()).willReturn(560.0);
    given(zoneStressTracker.isZoneStressed()).willReturn(ZONE_STRESSED);
    given(triggerEvaluator.shouldTrigger(600.0, 560.0, ZONE_STRESSED)).willReturn(true);
    given(triggerEvaluator.effectiveMarginAmps(ZONE_STRESSED)).willReturn(50.0);
    MutableClock clock = new MutableClock(NOW);
    EventOrchestrator orchestrator =
        new EventOrchestrator(
            ratingSubscriber,
            loadGenerator,
            triggerEvaluator,
            zoneStressTracker,
            client,
            config,
            clock);

    // Act
    orchestrator.tick(); // dispatch
    clock.advance(java.time.Duration.ofHours(5));
    orchestrator.tick(); // still triggering, but past max duration -> must close anyway

    // Assert
    ArgumentCaptor<DerEventRequest> request = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client, times(2)).dispatch(request.capture());
    assertThat(request.getAllValues().get(1).eventStatus()).isEqualTo("CANCELLED");
  }
}
