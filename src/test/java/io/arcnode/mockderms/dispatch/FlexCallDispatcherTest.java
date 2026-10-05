package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — the contracted flex call, which is the one event this utility has a basis to send as a
 * power command rather than an envelope. Mocked collaborators, AAA.
 */
@ExtendWith(MockitoExtension.class)
class FlexCallDispatcherTest {

  @Mock private ZoneStressTracker zoneStressTracker;
  @Mock private DerEventsClient client;

  private final DispatchFixtures.MutableClock clock =
      new DispatchFixtures.MutableClock(DispatchFixtures.NOW);

  private FlexCallDispatcher dispatcher() {
    return new FlexCallDispatcher(zoneStressTracker, client, DispatchFixtures.config(), clock);
  }

  private DerEventRequest captureOne() {
    ArgumentCaptor<DerEventRequest> sent = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client).dispatch(sent.capture(), any());
    return sent.getValue();
  }

  @Test
  void commandsTheContractedDepthWhenTheZoneIsStressed() {
    // Arrange: the program this site is enrolled in entitles the utility to call its full depth
    given(zoneStressTracker.isZoneStressed()).willReturn(true);

    // Act
    dispatcher().tick();

    // Assert: 100% of the enrolled 1120 kW, positive because the site answers by discharging
    DerEventRequest sent = captureOne();
    assertThat(sent.derControlBase().opModTargetW()).isEqualTo(1_120_000.0);
    assertThat(sent.eventStatus()).isEqualTo("ACTIVE");
    // Reason: a commanded event must carry no limit. der-control-api treats target-less events
    // with a limit as envelope-only and never publishes event_active or target_setpoint_present
    // for them, so adding one here would silently put this back on the envelope path.
    assertThat(sent.derControlBase().opModImpLimW()).isNull();
    assertThat(sent.derControlBase().opModExpLimW()).isNull();
  }

  @Test
  void sendsNothingWhileTheZoneIsCalm() {
    // Arrange
    given(zoneStressTracker.isZoneStressed()).willReturn(false);

    // Act
    dispatcher().tick();

    // Assert: this program is called on system need, not on a timer
    verify(client, never()).dispatch(any(), any());
  }

  @Test
  void closesTheCallWithTheSameMridOnceItsContractedDurationIsUp() {
    // Arrange: stress persists, so only the duration can end this
    given(zoneStressTracker.isZoneStressed()).willReturn(true);
    FlexCallDispatcher dispatcher = dispatcher();
    dispatcher.tick();
    String openedMrid = captureOne().mrid();

    // Act
    clock.advance(Duration.ofHours(5));
    dispatcher.tick();

    // Assert: same mRID with a terminal status is how an event is closed — der-control-api derives
    // event_active rather than exposing it, so a consumer only releases when the event itself ends
    ArgumentCaptor<DerEventRequest> sent = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client, times(2)).dispatch(sent.capture(), any());
    DerEventRequest close = sent.getAllValues().get(1);
    assertThat(close.mrid()).isEqualTo(openedMrid);
    assertThat(close.eventStatus()).isEqualTo("COMPLETED");
  }

  @Test
  void refusesToCallAgainInsideTheContractedRecoveryInterval() {
    // Arrange: a call that has run its course, with the zone still stressed afterwards
    given(zoneStressTracker.isZoneStressed()).willReturn(true);
    FlexCallDispatcher dispatcher = dispatcher();
    dispatcher.tick();
    clock.advance(Duration.ofHours(5));
    dispatcher.tick();

    // Act
    clock.advance(Duration.ofHours(1));
    dispatcher.tick();

    // Assert: the minimum interval between events is a contract term, and it is the term the
    // site's recharge rate was sized against — calling again sooner asks for energy the plant was
    // never built to have refilled. Two dispatches, not three.
    verify(client, times(2)).dispatch(any(), any());
  }
}
