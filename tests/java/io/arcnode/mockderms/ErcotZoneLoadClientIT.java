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
 * Real OAuth2 ROPC + archive-download flow against a stubbed ERCOT, plus the synthetic fallback.
 * Both {@code ercotTokenUrl}/{@code ercotArchiveUrl} are WireMock-pointed for the happy path so the
 * test never depends on live ERCOT credentials or network access (this repo's CI has neither {@code
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
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"archives\": [{\"docId\": 42}]}")));
    wiremock.stubFor(
        get(urlPathEqualTo("/archive/np3-562-cd"))
            .withQueryParam("download", equalTo("42"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/octet-stream")
                    .withBody(zip(CSV))));

    // Act
    double result = client.currentNorthZoneLoadMw();

    // Assert
    assertThat(result).isEqualTo(1714.08);
  }

  @Test
  void fallsBackToSyntheticWhenErcotAuthFails() {
    // Arrange
    wiremock.stubFor(post("/oauth2/token").willReturn(status(401)));

    // Act
    double result = client.currentNorthZoneLoadMw();

    // Assert
    assertThat(result)
        .isBetween(ErcotZoneLoadClient.SYNTHETIC_MIN_MW, ErcotZoneLoadClient.SYNTHETIC_MAX_MW);
  }
}
