package io.arcnode.mockderms;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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

  @Autowired Config config;
  @Autowired DerEventsClient client;

  @BeforeEach
  void setup() {
    // Reason: e2e profile (beta) points at the real endpoint — skip the stubbed assertion there.
    Assumptions.assumeFalse(config.e2e(), "e2e profile hits the real downstream");
  }

  private static DerEventRequest request() {
    return new DerEventRequest(
        "mrid-1",
        "ACTIVE",
        new DerEventRequest.Interval(Instant.parse("2026-09-21T00:00:00Z"), 3600L),
        new DerEventRequest.ControlBase(500_000.0, true, null, null));
  }

  @Test
  void postsToDerEventsWithTheMockUtilityIdentityHeader() {
    // Arrange
    wiremock.stubFor(post("/der-events").willReturn(status(201)));

    // Act
    client.dispatch(request());

    // Assert
    wiremock.verify(
        postRequestedFor(urlEqualTo("/der-events"))
            .withHeader("X-SSL-Client-Cert", equalTo(MockUtilityIdentity.HEADER_VALUE))
            .withRequestBody(matchingJsonPath("$.mrid", equalTo("mrid-1")))
            .withRequestBody(
                matchingJsonPath("$.derControlBase.opModTargetW", equalTo("500000.0"))));
  }
}
