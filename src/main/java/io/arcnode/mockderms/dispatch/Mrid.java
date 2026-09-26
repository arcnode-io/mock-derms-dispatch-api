package io.arcnode.mockderms.dispatch;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Mints IEEE 2030.5 mRIDs. sep.xsd requires structure rather than plain randomness: the IANA PEN
 * provider ID occupies bits 0-31 (the least-significant bits) and the provider assigns unique IDs
 * in the remaining 96 bits.
 *
 * <p>ARCNODE holds no registered enterprise number, so this uses 32473 — the PEN RFC 5612 reserves
 * for documentation and examples — which is the honest choice for a mock utility. A real deployment
 * has to register its own and substitute it here.
 */
public final class Mrid {

  private static final int DOCUMENTATION_PEN = 32473;
  private static final int UNIQUE_ID_BYTES = 12;
  private static final SecureRandom RANDOM = new SecureRandom();

  private Mrid() {}

  /** A fresh mRID as 32 lowercase hex characters. */
  public static String next() {
    byte[] uniqueId = new byte[UNIQUE_ID_BYTES];
    RANDOM.nextBytes(uniqueId);
    return HexFormat.of().formatHex(uniqueId) + "%08x".formatted(DOCUMENTATION_PEN);
  }
}
