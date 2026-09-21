package io.arcnode.mockderms;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

/** Unit — {@code $ENV} block selection and {@link Config} constraints. AAA. */
class ConfigTest {

  private final Config.Loader loader = new Config.Loader();

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
    assertThat(env.getProperty("app.derControlApiUrl")).isEqualTo("http://localhost:8080");
    assertThat(env.getProperty("app.nominalLineVoltageKv", Double.class)).isEqualTo(13.8);
    assertThat(env.getProperty("app.triggerMarginAmps", Double.class)).isEqualTo(50.0);
    assertThat(env.getProperty("app.maxEventDurationHours", Double.class)).isEqualTo(4.0);
  }

  @Test
  void unknownEnvFallsBackToLocalBlock() {
    // Arrange: the CI runner exports ENV=ci — must behave like the siblings (fall through to local)
    MockEnvironment env = new MockEnvironment().withProperty("ENV", "ci");

    // Act
    loader.postProcessEnvironment(env, new SpringApplication());

    // Assert
    assertThat(env.getProperty("app.siteId")).isEqualTo("site_001");
    assertThat(env.getProperty("app.e2e", Boolean.class)).isFalse();
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
            "http://localhost:8080",
            13.8,
            50.0,
            4.0);

    // Act
    var violations = validator.validate(bad);

    // Assert
    assertThat(violations).hasSize(2);
  }
}
