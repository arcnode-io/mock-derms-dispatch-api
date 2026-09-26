package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — the standing CSIP-AUS operating envelope: how much the site is allowed to import right
 * now, sent continuously rather than only during a curtailment. It constrains and commands nothing,
 * so it carries envelope modes and no setpoint. Mocked client, fixed clock, AAA.
 */
@ExtendWith(MockitoExtension.class)
class EnvelopeDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  @Mock private DerEventsClient client;

  private EnvelopeDispatcher dispatcher(boolean enabled) {
    return new EnvelopeDispatcher(client, DispatchFixtures.config(enabled), CLOCK);
  }

  private DerEventRequest captureSent() {
    ArgumentCaptor<DerEventRequest> request = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client).dispatch(request.capture(), any());
    return request.getValue();
  }

  @Test
  void sendsTheHeadroomLeftAboveTheTriggerMargin() {
    // Arrange: rating 200A, trigger margin 50A, other customers' load 100A -> 50A of headroom
    dispatcher(true).publish(200.0, 100.0);

    // Assert: sqrt(3) x 13.8kV x 1000 x 50A
    assertThat(captureSent().derControlBase().opModImpLimW()).isCloseTo(1_195_115.06, within(0.01));
  }

  @Test
  void clampsTheImportLimitAtZeroWhenTheLineIsAlreadyInsideTheMargin() {
    // Arrange: loading has already eaten the whole margin
    dispatcher(true).publish(100.0, 100.0);

    // Assert: no headroom, and never a negative limit
    assertThat(captureSent().derControlBase().opModImpLimW()).isEqualTo(0.0);
  }

  @Test
  void declaresANonExportSite() {
    // Act
    dispatcher(true).publish(200.0, 100.0);

    // Assert
    assertThat(captureSent().derControlBase().opModExpLimW()).isEqualTo(0.0);
  }

  @Test
  void carriesNoSetpointSoItCannotBeMistakenForADispatch() {
    // Act
    dispatcher(true).publish(200.0, 100.0);

    // Assert: der-control-api reads exactly this to keep the envelope off der_dispatch's channels
    DerEventRequest sent = captureSent();
    assertThat(sent.derControlBase().opModTargetW()).isNull();
    assertThat(sent.derControlBase().opModEnergize()).isNull();
  }

  @Test
  void reusesOneMridSoTheEnvelopeIsUpdatedInPlace() {
    // Arrange
    EnvelopeDispatcher dispatcher = dispatcher(true);

    // Act
    dispatcher.publish(200.0, 100.0);
    dispatcher.publish(200.0, 120.0);

    // Assert: a fresh mRID per tick would accumulate a new event row every five seconds
    ArgumentCaptor<DerEventRequest> request = ArgumentCaptor.forClass(DerEventRequest.class);
    verify(client, org.mockito.Mockito.times(2)).dispatch(request.capture(), any());
    assertThat(request.getAllValues().get(0).mrid())
        .isEqualTo(request.getAllValues().get(1).mrid());
  }

  @Test
  void mintsASpecShapedMridForTheEnvelope() {
    // Act
    dispatcher(true).publish(200.0, 100.0);

    // Assert
    assertThat(captureSent().mrid()).matches("[0-9a-f]{32}");
  }

  @Test
  void windowOutlastsOneTickSoTheEnvelopeNeverLapsesBetweenUpdates() {
    // Act
    dispatcher(true).publish(200.0, 100.0);

    // Assert
    DerEventRequest sent = captureSent();
    assertThat(sent.interval().start()).isEqualTo(NOW);
    assertThat(sent.interval().durationSeconds()).isGreaterThan(5L);
  }

  @Test
  void sendsNothingWhileTheScheduleIsDisabled() {
    // Act
    dispatcher(false).publish(200.0, 100.0);

    // Assert
    verify(client, never()).dispatch(any(), any());
  }
}
