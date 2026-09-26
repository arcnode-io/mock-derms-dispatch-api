package io.arcnode.mockderms;

import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.arcnode.mockderms.dispatch.DerEventsClient;
import io.arcnode.mockderms.dispatch.MockUtilityIdentity;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import java.time.Instant;
import java.util.Locale;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/** POST /der-events over real HTTP, downstream stubbed — mirrors CallApiResourceIT's pattern. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DerEventsClientIT extends AbstractBrokerIT {

  @RegisterExtension
  static WireMockExtension wiremock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("app.derControlApiUrl", wiremock::baseUrl);
  }

  // Reason: mRIDType is HexBinary128 — 32 hex characters — so a readable label cannot be one.
  private static final String MRID = "0123456789abcdef0123456789abcdef";
  private static final Instant CREATED_AT = Instant.parse("2026-09-21T00:00:00Z");

  @Autowired Config config;
  @Autowired DerEventsClient client;

  @BeforeEach
  void setup() {
    // Reason: e2e profile (beta) points at the real endpoint — skip the stubbed assertion there.
    Assumptions.assumeFalse(config.e2e(), "e2e profile hits the real downstream");
  }

  private static DerEventRequest request() {
    return new DerEventRequest(
        MRID,
        "ACTIVE",
        new DerEventRequest.Interval(Instant.parse("2026-09-21T00:00:00Z"), 3600L),
        new DerEventRequest.ControlBase(500_000.0, true, null, null));
  }

  @Test
  void postsToDerEventsWithTheMockUtilityIdentityHeader() {
    // Arrange
    wiremock.stubFor(post("/der-events").willReturn(status(201)));

    // Act
    client.dispatch(request(), CREATED_AT);

    // Assert
    wiremock.verify(
        postRequestedFor(urlEqualTo("/der-events"))
            .withHeader("X-SSL-Client-Cert", equalTo(MockUtilityIdentity.HEADER_VALUE))
            .withHeader("Content-Type", containing("application/sep+xml"))
            // Reason: the body is a real IEEE 2030.5 Notification, so the assertions are about the
            // spec's own encodings — a 32-hex-character mRID and a scaled ActivePower, not a plain
            // watts number, which an Int16 mantissa could not carry anyway.
            .withRequestBody(containing("<mRID>" + MRID.toUpperCase(Locale.ROOT) + "</mRID>"))
            .withRequestBody(containing("<multiplier>2</multiplier><value>5000</value>"))
            .withRequestBody(containing("xsi:type=\"DERControlList\"")));
  }
}
