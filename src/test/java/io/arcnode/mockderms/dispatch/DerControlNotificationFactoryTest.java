package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import io.arcnode.mockderms.mirror.Ieee20305Xml;
import io.arcnode.mockderms.mirror.ieee20305.DERControl;
import io.arcnode.mockderms.mirror.ieee20305.NotificationElement;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * Unit — builds the real IEEE 2030.5 document the utility puts on the wire: a {@code Notification}
 * carrying a full {@code DERControl} in its {@code Resource} slot, which is the mechanism sep.xsd
 * itself describes ("the actual resources may be passed in the Notification by specifying a
 * specific xsi:type for the Resource"). The happy-path assertion is validation against the real
 * sep.xsd, because that is the only thing that catches a well-typed but semantically wrong value —
 * a missing mandatory field or an out-of-range code. AAA.
 */
class DerControlNotificationFactoryTest {

  // Reason: mRIDType is HexBinary128 — exactly 16 bytes, so 32 hex characters and no hyphens.
  private static final String MRID = "0123456789abcdef0123456789abcdef";
  private static final Instant CREATED_AT = Instant.parse("2026-09-25T18:00:00Z");
  private static final Instant START = Instant.parse("2026-09-25T18:00:05Z");
  private static final String BASE_URL = "https://mock-derms.invalid";

  private static DerEventRequest curtailment() {
    return new DerEventRequest(
        MRID,
        "ACTIVE",
        new DerEventRequest.Interval(START, 360L),
        new DerEventRequest.ControlBase(1_500_000.0, true, null, null));
  }

  @Test
  void buildsANotificationThatValidatesAgainstTheRealIeee20305Schema() {
    // Arrange
    DerEventRequest request = curtailment();

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(request, CREATED_AT, BASE_URL);
    String xml = Ieee20305Xml.marshal(notification);

    // Assert
    assertThatCode(() -> Ieee20305Xml.validate(xml)).doesNotThrowAnyException();
  }

  @Test
  void carriesTheTargetPowerScaledIntoTheInt16Mantissa() {
    // Arrange
    DerEventRequest request = curtailment();

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(request, CREATED_AT, BASE_URL);
    DERControl control = (DERControl) notification.getResource();

    // Assert: 1.5 MW cannot fit an Int16 unscaled
    assertThat(control.getDERControlBase().getOpModTargetW().getValue()).isEqualTo((short) 15_000);
    assertThat(control.getDERControlBase().getOpModTargetW().getMultiplier().getValue())
        .isEqualTo((byte) 2);
  }

  @Test
  void carriesTheMridAsSixteenRawBytesNotAHyphenatedString() {
    // Arrange
    DerEventRequest request = curtailment();

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(request, CREATED_AT, BASE_URL);
    DERControl control = (DERControl) notification.getResource();

    // Assert
    assertThat(control.getMRID().getValue()).hasSize(16);
  }

  @Test
  void carriesTheMandatoryEventStatusFieldsWithTheSpecsOwnStatusCode() {
    // Arrange
    DerEventRequest request = curtailment();

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(request, CREATED_AT, BASE_URL);
    DERControl control = (DERControl) notification.getResource();

    // Assert: EventStatus is a complex type, not a bare string — currentStatus 1 = Active
    assertThat(control.getEventStatus().getCurrentStatus()).isEqualTo((short) 1);
    assertThat(control.getEventStatus().getDateTime().getValue())
        .isEqualTo(CREATED_AT.getEpochSecond());
    assertThat(control.getEventStatus().isPotentiallySuperseded()).isFalse();
  }

  @Test
  void carriesTimesAsEpochSecondsNotIso8601() {
    // Arrange
    DerEventRequest request = curtailment();

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(request, CREATED_AT, BASE_URL);
    DERControl control = (DERControl) notification.getResource();

    // Assert: TimeType is an Int64 count of seconds since the epoch
    assertThat(control.getCreationTime().getValue()).isEqualTo(CREATED_AT.getEpochSecond());
    assertThat(control.getInterval().getStart().getValue()).isEqualTo(START.getEpochSecond());
    assertThat(control.getInterval().getDuration()).isEqualTo(360L);
  }

  @Test
  void closesAnEventWithTheCompletedStatusCode() {
    // Arrange: a close carries a terminal status and no setpoint
    DerEventRequest close =
        new DerEventRequest(
            MRID,
            "COMPLETED",
            new DerEventRequest.Interval(START, 360L),
            new DerEventRequest.ControlBase(null, null, null, null));

    // Act
    NotificationElement notification =
        DerControlNotificationFactory.build(close, CREATED_AT, BASE_URL);
    DERControl control = (DERControl) notification.getResource();
    String xml = Ieee20305Xml.marshal(notification);

    // Assert: currentStatus 5 = Completed, and an empty DERControlBase still validates
    assertThat(control.getEventStatus().getCurrentStatus()).isEqualTo((short) 5);
    assertThatCode(() -> Ieee20305Xml.validate(xml)).doesNotThrowAnyException();
  }
}
