package io.arcnode.mockderms;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Health check over real HTTP — the {@code tests/app.test.ts} analog. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class AppResourceIT extends AbstractBrokerIT {

  @LocalServerPort int port;
  RestTestClient rest;

  // Reason: Boot 4.1 has no @AutoConfigureRestTestClient yet (lands in 4.2) — bind by hand.
  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void healthCheckReturnsOk() {
    // Arrange / Act / Assert
    rest.get().uri("/").exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("ok");
  }

  @Test
  void actuatorHealthIsUp() {
    rest.get()
        .uri("/actuator/health")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody()
        .jsonPath("$.status")
        .isEqualTo("UP");
  }

  @Test
  void openApiSpecIsServed() {
    rest.get().uri("/v3/api-docs").exchange().expectStatus().isOk();
  }
}
