package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import io.arcnode.mockderms.mirror.ieee20305.ActivePowerControlType;
import io.arcnode.mockderms.mirror.ieee20305.DERControl;
import io.arcnode.mockderms.mirror.ieee20305.DERControlBase;
import io.arcnode.mockderms.mirror.ieee20305.DateTimeInterval;
import io.arcnode.mockderms.mirror.ieee20305.EventStatus;
import io.arcnode.mockderms.mirror.ieee20305.MRIDType;
import io.arcnode.mockderms.mirror.ieee20305.NotificationElement;
import io.arcnode.mockderms.mirror.ieee20305.PowerOfTenMultiplierType;
import io.arcnode.mockderms.mirror.ieee20305.TimeType;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Builds the IEEE 2030.5 document this mock utility puts on the wire: a {@code Notification}
 * carrying a full {@code DERControl} in its {@code Resource} slot. sep.xsd documents that mechanism
 * directly — "the actual resources may be passed in the Notification by specifying a specific
 * xsi:type for the Resource and passing the full representation" — so a server-to-client push of a
 * control is the spec's own subscription delivery, not an inversion of it.
 *
 * <p>This is the compliance boundary. Everything inside this service stays in natural Java types
 * (signed watts as a {@code double}, an {@code Instant}, a readable status name); the spec's own
 * encodings — a 16-byte mRID, epoch-second times, a scaled {@code Int16} power mantissa — exist
 * only from here outward.
 */
public final class DerControlNotificationFactory {

  // Reason: matches sep.xsd's own root <xs:schema version="2.2">; the spec prose requires every
  // top-level XML element to carry schemaVer, and the attribute's XSD default is stale.
  private static final String SCHEMA_VERSION = "2.2";
  // Reason: Notification::status 0 = "Default Status" per sep.xsd. The non-zero codes all mean the
  // subscription was cancelled, which is not what a routine control push reports.
  private static final short NOTIFICATION_STATUS_DEFAULT = 0;
  // Reason: 2030.5 resources are discovered by following links, so the spec fixes no URI paths.
  // These are ours; only their absoluteness is a schema requirement (xs:anyURI, "SHALL be a
  // fully-qualified absolute URI").
  private static final String DER_CONTROL_LIST_PATH = "/derp/1/derc";
  private static final String SUBSCRIPTION_PATH = "/sub/1";

  private DerControlNotificationFactory() {}

  /**
   * @param request what this service decided to dispatch, in its own internal types
   * @param creationTime when the control was created — {@code Event::creationTime} is mandatory
   * @param publicBaseUrl this service's own externally-reachable base URL, used to build the
   *     absolute subscription URIs the schema requires
   */
  public static NotificationElement build(
      DerEventRequest request, Instant creationTime, String publicBaseUrl) {
    NotificationElement notification = new NotificationElement();
    notification.setSchemaVer(SCHEMA_VERSION);
    notification.setSubscribedResource(publicBaseUrl + DER_CONTROL_LIST_PATH);
    notification.setSubscriptionURI(publicBaseUrl + SUBSCRIPTION_PATH);
    notification.setStatus(NOTIFICATION_STATUS_DEFAULT);
    notification.setCreatedDateTime(time(creationTime));
    notification.setResource(derControl(request, creationTime));
    return notification;
  }

  private static DERControl derControl(DerEventRequest request, Instant creationTime) {
    DERControl control = new DERControl();
    control.setMRID(mrid(request.mrid()));
    control.setCreationTime(time(creationTime));
    control.setInterval(interval(request.interval()));
    control.setEventStatus(eventStatus(request.eventStatus(), creationTime));
    control.setDERControlBase(controlBase(request.derControlBase()));
    return control;
  }

  private static MRIDType mrid(String hex) {
    MRIDType mrid = new MRIDType();
    mrid.setValue(HexFormat.of().parseHex(hex));
    return mrid;
  }

  private static TimeType time(Instant instant) {
    TimeType time = new TimeType();
    time.setValue(instant.getEpochSecond());
    return time;
  }

  private static DateTimeInterval interval(DerEventRequest.Interval source) {
    DateTimeInterval interval = new DateTimeInterval();
    interval.setStart(time(source.start()));
    interval.setDuration(source.durationSeconds());
    return interval;
  }

  private static EventStatus eventStatus(String name, Instant changedAt) {
    EventStatus status = new EventStatus();
    status.setCurrentStatus(DerControlStatus.valueOf(name).currentStatus());
    status.setDateTime(time(changedAt));
    // Reason: mandatory, and this mock never issues overlapping controls for the same DER, so no
    // control it sends can be superseded by another.
    status.setPotentiallySuperseded(false);
    return status;
  }

  private static DERControlBase controlBase(DerEventRequest.ControlBase source) {
    if (source.opModImpLimW() != null || source.opModExpLimW() != null) {
      throw new UnsupportedOperationException(
          "envelope limits are CSIP-AUS extensions and must be emitted in a non-sep namespace");
    }
    DERControlBase base = new DERControlBase();
    Double targetWatts = source.opModTargetW();
    if (targetWatts != null) {
      base.setOpModTargetW(activePower(targetWatts));
    }
    Boolean energize = source.opModEnergize();
    if (energize != null) {
      base.setOpModEnergize(energize);
    }
    return base;
  }

  private static ActivePowerControlType activePower(double watts) {
    ScaledActivePower scaled = ScaledActivePower.ofWatts(watts);
    PowerOfTenMultiplierType multiplier = new PowerOfTenMultiplierType();
    multiplier.setValue(scaled.multiplier());
    ActivePowerControlType power = new ActivePowerControlType();
    power.setValue(scaled.value());
    power.setMultiplier(multiplier);
    return power;
  }
}
