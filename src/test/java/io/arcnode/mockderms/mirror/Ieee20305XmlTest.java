package io.arcnode.mockderms.mirror;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.mockderms.mirror.ieee20305.MirrorUsagePoint;
import org.junit.jupiter.api.Test;

/**
 * Unit — parses a real IEEE 2030.5 {@code MirrorUsagePoint} document (the shape der-control-api's
 * own MirrorUsagePointFactory produces) back into the JAXB object graph. AAA.
 */
class Ieee20305XmlTest {

  // Reason: real shape, matching der-control-api's own MirrorUsagePointFactory output exactly —
  // deviceLFDI/mRID/roleFlags/serviceCategoryKind, one MirrorMeterReading with kind=37 (Power),
  // uom=38 (W), multiplier=0, and the actual Reading.value.
  private static final String REAL_SHAPE_XML =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
      <MirrorUsagePoint xmlns="urn:ieee:std:2030.5:ns" schemaVer="2.2">
          <mRID>00112233445566778899001122334455</mRID>
          <deviceLFDI>0011223344556677889900112233445566778899</deviceLFDI>
          <roleFlags>0009</roleFlags>
          <serviceCategoryKind>0</serviceCategoryKind>
          <MirrorMeterReading>
              <mRID>11002233445566778899001122334455</mRID>
              <ReadingType>
                  <kind>37</kind>
                  <powerOfTenMultiplier>0</powerOfTenMultiplier>
                  <uom>38</uom>
              </ReadingType>
              <Reading>
                  <value>612500</value>
              </Reading>
          </MirrorMeterReading>
      </MirrorUsagePoint>
      """;

  @Test
  void parsesTheActualWattsReadingFromARealMirrorUsagePointDocument() {
    // Act
    MirrorUsagePoint usagePoint = Ieee20305Xml.unmarshal(REAL_SHAPE_XML);

    // Assert
    assertThat(usagePoint.getMirrorMeterReading()).hasSize(1);
    assertThat(usagePoint.getMirrorMeterReading().get(0).getReading().getValue())
        .isEqualTo(612_500L);
  }
}
