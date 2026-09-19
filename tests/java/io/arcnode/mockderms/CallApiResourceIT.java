package io.arcnode.mockderms;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Outbound call over real HTTP, downstream stubbed — the {@code call-api.test.ts} analog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CallApiResourceIT extends AbstractBrokerIT {

  @RegisterExtension
  static WireMockExtension wiremock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("call-api.base-url", wiremock::baseUrl);
  }

  @LocalServerPort int port;
  @Autowired Config config;
  RestTestClient rest;

  @BeforeEach
  void setup() {
    // Reason: e2e profile (beta) points at the real endpoint — skip the stubbed assertion there.
    Assumptions.assumeFalse(config.e2e(), "e2e profile hits the real downstream");
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void returnsDownstreamUrl() {
    // Arrange
    wiremock.stubFor(
        get("/get").willReturn(okJson("{\"url\":\"https://mocked-api.example.com/get\"}")));

    // Act + Assert
    rest.get()
        .uri("/call-api")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.url")
        .isEqualTo("https://mocked-api.example.com/get");
  }
}
