package io.arcnode.mockderms.dispatch;

/**
 * Lifecycle state of a DERControl event, carrying the {@code EventStatus.currentStatus} code
 * sep.xsd assigns it. The wire format needs the number; the name is what this service's own code
 * reads.
 *
 * <p>Code 3 ("Cancelled with Randomization") folds into {@link #CANCELLED} for MVP, since nothing
 * here randomizes start or duration. Code 4 ({@link #SUPERSEDED}) is deprecated in the 2023 edition
 * ("SHALL NOT be used by servers") and is present only so a name exists for it.
 */
public enum DerControlStatus {
  SCHEDULED(0),
  ACTIVE(1),
  CANCELLED(2),
  SUPERSEDED(4),
  COMPLETED(5);

  private final short currentStatus;

  DerControlStatus(int currentStatus) {
    this.currentStatus = (short) currentStatus;
  }

  /** The {@code EventStatus.currentStatus} code for this state. */
  public short currentStatus() {
    return currentStatus;
  }
}
