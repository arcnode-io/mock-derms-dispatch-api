package io.arcnode.mockderms;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;
import org.yaml.snakeyaml.Yaml;

/**
 * Non-secret app config, bound from the {@code app.*} keys that {@link Loader} lifts out of {@code
 * cfg.yml} for the active {@code ENV}. The zod/Pydantic {@code Config} model analog: one validated
 * value object, not scattered {@code @Value} injections — one file, same as the sibling templates'
 * single {@code config.py}/{@code config.ts} (nested types stay public; Java only requires one
 * *public top-level* type per file, matching the filename, not one type total).
 *
 * @param logLevel root log level
 * @param port HTTP listen port
 * @param host HTTP bind address
 * @param e2e when true, outbound calls hit the real endpoint instead of a WireMock stub
 * @param mqttBrokerUrl deployment broker URI, e.g. {@code tcp://hivemq:1883}
 * @param mqttUsername broker File-RBAC identity ({@code arcnode_mock_derms_dispatch_api}); password
 *     is a secret
 * @param siteId site slug for the {@code sites/{siteId}/devices/...} topics this service consumes
 *     (dlr_rtu rating, compliance return path) — same site der_control_api dispatches to
 * @param derControlApiUrl base URL for {@code POST /der-events} (constraint dispatch + event close)
 * @param nominalLineVoltageKv fixed per-deployment line voltage — converts {@code
 *     dynamic_line_rating} (amps, IEEE 738 ampacity) to a power headroom, done once here rather
 *     than the utility's own grid infrastructure being something our topology would ever model
 * @param triggerMarginAmps safety margin below rating before the real-time trigger check fires
 * @param maxEventDurationHours hard cap on how long one curtailment event may stay open,
 *     independent of whether the line rating has recovered
 * @param dlrDeviceId device_id of the {@code dlr_rtu} instance whose {@code dynamic_line_rating}
 *     this service subscribes to — an RTU's device_id is a per-commissioning instance identifier,
 *     never guaranteed to match its template slug, so this is config, not a compile-time constant
 */
@ConfigurationProperties(prefix = "app")
@Validated
public record Config(
    @NotNull LogLevel logLevel,
    @Min(80) int port,
    @NotBlank String host,
    boolean e2e,
    @NotBlank String mqttBrokerUrl,
    @NotBlank String mqttUsername,
    @NotBlank String siteId,
    @NotBlank String derControlApiUrl,
    double nominalLineVoltageKv,
    double triggerMarginAmps,
    double maxEventDurationHours,
    @NotBlank String dlrDeviceId) {

  /** Log levels accepted in {@code cfg.yml} — mirrors the sibling templates. */
  public enum LogLevel {
    ERROR,
    WARN,
    INFO,
    DEBUG
  }

  /**
   * Reads {@code cfg.yml} and feeds the active block into the Spring {@code Environment}. Direct
   * analog of {@code loadConfig()} in the nestjs / fastapi templates: same file, same {@code local}
   * / {@code beta} keys, same {@code ENV} var, same rule — {@code ENV=beta} selects {@code beta},
   * anything else (unset, {@code local}, {@code ci}, …) selects {@code local}.
   *
   * <p>Every entry lands under {@code app.*} for the enclosing {@link Config} record. {@code
   * logLevel} is also published as {@code logging.level.root} directly (not a placeholder) because
   * Boot binds the log level before placeholder resolution is available. {@code server.port} /
   * {@code server.address} stay as {@code ${app.*}} placeholders in {@code application.yml} so
   * {@code @SpringBootTest} can still override the port.
   *
   * <p>Registered in {@code META-INF/spring.factories} as {@code Config$Loader} — nesting doesn't
   * affect reflective accessibility, Spring's {@code SpringFactoriesLoader} resolves the binary
   * name the same way it would a top-level class.
   */
  public static class Loader implements EnvironmentPostProcessor {

    private static final String CONFIG_FILE = "cfg.yml";
    private static final String BETA = "beta";
    private static final String DEFAULT_BLOCK = "local";
    private static final String PROPERTY_SOURCE_NAME = "cfg.yml";

    /**
     * cfg.yml key -> a Spring-native property that is bound too early for a {@code ${...}}
     * placeholder.
     */
    private static final Map<String, String> NATIVE_KEYS = Map.of("logLevel", "logging.level.root");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication app) {
      String env =
          environment.getProperty("ENV", System.getenv().getOrDefault("ENV", DEFAULT_BLOCK));
      Map<String, Object> block = readBlock(BETA.equals(env) ? BETA : DEFAULT_BLOCK);

      Map<String, Object> resolved = new LinkedHashMap<>();
      block.forEach(
          (key, value) -> {
            resolved.put("app." + key, value);
            String nativeKey = NATIVE_KEYS.get(key);
            if (nativeKey != null) {
              resolved.put(nativeKey, value);
            }
          });

      // Reason: addFirst so cfg.yml is authoritative for its keys (beats application.yml + the
      // framework log-level default). Test property sources (@DynamicPropertySource) still layer
      // above.
      environment
          .getPropertySources()
          .addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, resolved));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readBlock(String blockName) {
      Resource resource = new FileSystemResource(CONFIG_FILE);
      if (!resource.exists()) {
        resource = new ClassPathResource(CONFIG_FILE);
      }
      if (!resource.exists()) {
        throw new IllegalStateException(CONFIG_FILE + " not found on filesystem or classpath");
      }
      try (InputStream in = resource.getInputStream()) {
        Map<String, Object> root = new Yaml().load(in);
        if (root == null || !root.containsKey(blockName)) {
          throw new IllegalStateException("no '" + blockName + "' block in " + CONFIG_FILE);
        }
        return (Map<String, Object>) root.get(blockName);
      } catch (IOException e) {
        throw new IllegalStateException("failed to read " + CONFIG_FILE, e);
      }
    }
  }
}
