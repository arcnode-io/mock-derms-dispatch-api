package io.arcnode.mockderms.dispatch;

/**
 * The DERPrograms this utility runs, as the resource paths a site subscribes to.
 *
 * <p>IEEE 2030.5 has no "reason" on a DERControl; the program an event belongs to is how the spec
 * says what it is for. One program per purpose, so a site can tell a conductor limit from a
 * contracted call without reading the control's shape.
 */
public final class DerPrograms {
  /**
   * Line-constraint curtailment — a conductor near its rating. Sends an envelope, never a setpoint.
   */
  public static final String LINE_CONSTRAINT = "/derp/1/derc";

  /** The contracted flex program — called on ERCOT zone stress. Sends a commanded setpoint. */
  public static final String FLEX = "/derp/2/derc";

  private DerPrograms() {}
}
