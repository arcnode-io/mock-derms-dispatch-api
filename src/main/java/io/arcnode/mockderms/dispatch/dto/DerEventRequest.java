package io.arcnode.mockderms.dispatch.dto;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * {@code POST /der-events} body — mirrors ems-der-control-api's own {@code DerControlRequest}
 * exactly (no shared library across these two repos; the wire contract is the only thing that has
 * to match, per this system's own polyglot-services convention).
 *
 * @param mrid 2030.5 mRID — the event's stable identity
 * @param eventStatus one of ems-der-control-api's {@code DerControlStatus} names (SCHEDULED,
 *     ACTIVE, CANCELLED, SUPERSEDED)
 * @param interval the event's scheduled window
 * @param derControlBase the commanded setpoint
 */
public record DerEventRequest(
    String mrid, String eventStatus, Interval interval, ControlBase derControlBase) {

  public record Interval(Instant start, long durationSeconds) {}

  /**
   * @param opModTargetW commanded real power target, watts — positive discharge, negative charge
   *     (matches bess_rack's own active_power convention)
   */
  public record ControlBase(
      @Nullable Double opModTargetW,
      @Nullable Boolean opModEnergize,
      @Nullable Double opModImpLimW,
      @Nullable Double opModExpLimW) {}
}
