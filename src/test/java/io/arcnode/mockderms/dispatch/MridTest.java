package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit — sep.xsd's mRIDType is HexBinary128 and requires structure, not just randomness: "The IANA
 * PEN provider ID SHALL be specified in bits 0-31, the least-significant bits, and objects created
 * by that provider SHALL be assigned unique IDs with the remaining 96 bits." AAA.
 */
class MridTest {

  @Test
  void isExactlyThirtyTwoLowercaseHexCharacters() {
    // Act
    String mrid = Mrid.next();

    // Assert: HexBinary128 is 16 bytes, so 32 hex characters and nothing else
    assertThat(mrid).hasSize(32).matches("[0-9a-f]{32}");
  }

  @Test
  void carriesThePenInTheLeastSignificantThirtyTwoBits() {
    // Act
    String mrid = Mrid.next();

    // Assert: 32473 = 0x7ed9, the enterprise number RFC 5612 reserves for documentation use
    assertThat(mrid).endsWith("00007ed9");
  }

  @Test
  void assignsADifferentUniqueIdEachTime() {
    // Act
    String first = Mrid.next();
    String second = Mrid.next();

    // Assert: the 96 bits above the PEN are what distinguishes two objects
    assertThat(first).isNotEqualTo(second);
  }
}
