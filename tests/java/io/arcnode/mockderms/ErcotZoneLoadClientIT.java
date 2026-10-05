package io.arcnode.mockderms;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.status;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.arcnode.mockderms.dispatch.ErcotZoneLoadClient;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.OptionalDouble;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Real OAuth2 ROPC + archive-download flow against a stubbed ERCOT, plus the no-reading path. Both
 * {@code ercotTokenUrl}/{@code ercotArchiveUrl} are WireMock-pointed for the happy path so the test
 * never depends on live ERCOT credentials or network access (this repo's CI has neither {@code
 * ERCOT_PASSWORD} nor {@code ERCOT_PRIMARY_KEY} set).
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class ErcotZoneLoadClientIT extends AbstractBrokerIT {

  @RegisterExtension
  static WireMockExtension wiremock =
      WireMockExtension.newInstance().options(wireMockConfig().dynamicPort()).build();

  @DynamicPropertySource
  static void downstream(DynamicPropertyRegistry registry) {
    registry.add("app.ercotTokenUrl", () -> wiremock.baseUrl() + "/oauth2/token");
    registry.add("app.ercotArchiveUrl", () -> wiremock.baseUrl() + "/archive/np3-562-cd");
  }

  @Autowired ErcotZoneLoadClient client;

  /** Longer than the client's own read timeout, so the stub outlasts it. */
  private static final Duration UNANSWERED = Duration.ofSeconds(20);

  private static final String CSV =
      """
      IntervalEnding,North,InUseFlag
      09/21/2026 08:25,1714.08,Y
      """;

  private static byte[] zip(String csvContent) {
    try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ZipOutputStream zos = new ZipOutputStream(bytes)) {
      zos.putNextEntry(new ZipEntry("report.csv"));
      zos.write(csvContent.getBytes(StandardCharsets.UTF_8));
      zos.closeEntry();
      zos.finish();
      return bytes.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Test
  void parsesRealNorthZoneLoadThroughTheFullAuthAndArchiveFlow() {
    // Arrange
    wiremock.stubFor(
        post("/oauth2/token")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id_token\": \"fake-token\"}")));
    wiremock.stubFor(
        get(urlPathEqualTo("/archive/np3-562-cd"))
            .withQueryParam("size", equalTo("1"))
            // Reason: real bug this caught retroactively — TokenResponse.idToken never matched
            // the real API's snake_case id_token field, so every archive call silently sent
            // "Bearer null" and this test still passed, because without this header assertion
            // WireMock matches the stub regardless of what Authorization value arrives.
            .withHeader("Authorization", equalTo("Bearer fake-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"archives\": [{\"docId\": 42}]}")));
    wiremock.stubFor(
        get(urlPathEqualTo("/archive/np3-562-cd"))
            .withQueryParam("download", equalTo("42"))
            .withHeader("Authorization", equalTo("Bearer fake-token"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/octet-stream")
                    .withBody(zip(CSV))));

    // Act
    OptionalDouble result = client.currentNorthZoneLoadMw();

    // Assert
    assertThat(result).hasValue(1714.08);
  }

  @Test
  void givesUpOnAnErcotThatNeverAnswersRatherThanBlockingTheControlLoop() {
    // Arrange: ERCOT accepts the connection and then says nothing. This is the real failure seen
    // in the demo network — api.ercot.com resolved but its TCP connect blackholed, so the call
    // hung instead of failing, and with no timeout it never came back.
    wiremock.stubFor(
        post("/oauth2/token")
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"id_token\": \"fake-token\"}")
                    .withFixedDelay((int) UNANSWERED.toMillis())));

    // Act
    long startedAt = System.nanoTime();
    OptionalDouble result = client.currentNorthZoneLoadMw();
    Duration waited = Duration.ofNanos(System.nanoTime() - startedAt);

    // Assert: absent and bounded. This runs on the scheduled control-loop thread, so a call that
    // never returns does not merely lose the zone reading — it starves the curtailment trigger
    // sharing that thread, which stops evaluating entirely with nothing logged.
    assertThat(result).isEmpty();
    assertThat(waited).isLessThan(UNANSWERED);
  }

  @Test
  void returnsNoReadingWhenErcotAuthFails() {
    // Arrange
    wiremock.stubFor(post("/oauth2/token").willReturn(status(401)));

    // Act
    OptionalDouble result = client.currentNorthZoneLoadMw();

    // Assert: absent, never a stand-in value — ZoneStressTracker decides what absence means
    assertThat(result).isEmpty();
  }
}
