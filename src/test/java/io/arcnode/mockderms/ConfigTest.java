package io.arcnode.mockderms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** Unit — {@code $ENV} block selection and {@link Config} constraints. AAA. */
class ConfigTest {

  private final Config.Loader loader = new Config.Loader();

  @Test
  void selectsTheBlockNamedByEnv() {
    // Arrange: device-demo shortens the event window so natural expiry happens on camera
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "device-demo");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.maxEventDurationHours", Double.class)).isEqualTo(0.1);
  }

  @Test
  void failsLoudlyWhenEnvNamesNoBlock() {
    // Arrange: silently falling back to local would run the wrong configuration and look like a
    // behaviour bug rather than a misconfiguration
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "nope");

    // Act / Assert
    assertThatThrownBy(() -> loader.postProcessEnvironment(env, new SpringApplication()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void defaultsToLocalBlock() {
    // Arrange
    MockEnvironment env = new MockEnvironment();

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.port", Integer.class)).isEqualTo(8080);
    assertThat(env.getProperty("app.siteId")).isEqualTo("site_001");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isFalse();
    assertThat(env.getProperty("app.nominalLineVoltageKv", Double.class)).isEqualTo(13.8);
    assertThat(env.getProperty("app.triggerMarginAmps", Double.class)).isEqualTo(50.0);
    assertThat(env.getProperty("app.maxEventDurationHours", Double.class)).isEqualTo(4.0);
    assertThat(env.getProperty("app.dlrDeviceId")).isEqualTo("dlr_rtu_demo");
    assertThat(env.getProperty("app.ercotTokenUrl"))
        .isEqualTo(
            "https://ercotb2c.b2clogin.com/ercotb2c.onmicrosoft.com/B2C_1_PUBAPI-ROPC-FLOW"
                + "/oauth2/v2.0/token");
    assertThat(env.getProperty("app.ercotArchiveUrl"))
        .isEqualTo("https://api.ercot.com/api/public-reports/archive/np3-562-cd");
    assertThat(env.getProperty("app.zoneStressThresholdMw", Double.class)).isEqualTo(1800.0);
    assertThat(env.getProperty("app.zoneStressMarginBoostAmps", Double.class)).isEqualTo(25.0);
    assertThat(env.getProperty("app.envelopeScheduleEnabled", Boolean.class)).isTrue();
  }

  @Test
  void resolvesTheCiBlockTheRunnerAsksFor() {
    // Arrange: the gitlab-runner host exports ENV=ci, so `ci` names a real block
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "ci");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("site_001");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isFalse();
  }

  @Test
  void deviceDemoOverridesOnlySiteIdAndTheEventWindow() {
    // Arrange: device-demo is merged from beta, so it must inherit the container hostnames — a
    // localhost broker inside the compose stack is the failure this guards
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "device-demo");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("demo-site");
    assertThat(env.getProperty("app.maxEventDurationHours", Double.class)).isEqualTo(0.1);
    assertThat(env.getProperty("app.mqttBrokerUrl")).isEqualTo("tcp://hivemq:1883");
    assertThat(env.getProperty("app.publicBaseUrl"))
        .isEqualTo("http://mock-derms-dispatch-api:8080");
    assertThat(env.getProperty("app.triggerMarginAmps", Double.class)).isEqualTo(50.0);
  }

  @Test
  void selectsBetaBlockWhenEnvIsBeta() {
    // Arrange
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "beta");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("arcnode_beta");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isTrue();
  }

  @Test
  void rejectsPortBelowEightyAndBlankHost() {
    // Arrange
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    Config bad =
        new Config(
            Config.LogLevel.INFO,
            20,
            "",
            false,
            "tcp://localhost:1883",
            "user",
            "site",
            "http://localhost:8081",
            13.8,
            50.0,
            4.0,
            "dlr_rtu_demo",
            "https://example.invalid/token",
            "https://example.invalid/archive",
            1800.0,
            25.0,
            false);

    // Act
    var violations = validator.validate(bad);

    // Assert
    assertThat(violations).hasSize(2);
  }
}
