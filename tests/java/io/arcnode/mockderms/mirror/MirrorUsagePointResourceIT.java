package io.arcnode.mockderms.mirror;

import io.arcnode.mockderms.AbstractBrokerIT;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real IEEE 2030.5 XML over real HTTP — der-control-api's own outbound shape, received for real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class MirrorUsagePointResourceIT extends AbstractBrokerIT {

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

  @LocalServerPort int port;
  RestTestClient rest;

  // Reason: Boot 4.1 has no @AutoConfigureRestTestClient yet (lands in 4.2) — bind by hand, same
  // as AppResourceIT.
  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void acceptsARealMirrorUsagePointDocument() {
    // Act / Assert
    rest.post()
        .uri("/mirror-usage-points")
        .contentType(MediaType.APPLICATION_XML)
        .body(REAL_SHAPE_XML)
        .exchange()
        .expectStatus()
        .isCreated();
  }
}
