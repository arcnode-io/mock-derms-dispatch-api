package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.FloatSample;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Arm's-length compliance verification — this utility's own independent comparison of what
 * der-control-api says it dispatched ({@code target_active_power}) against what the site actually
 * delivered ({@code actual_active_power}), both already-published measurements on the same
 * der_dispatch topic {@code ems-hmi} consumes. Deliberately not a re-read of der-control-api's own
 * {@code dispatch_shortfall}/{@code dispatch_overdelivery} — a real utility with contractual/
 * regulatory stakes verifies compliance itself rather than trusting the site's self-report.
 * Subscribe-only: this service never publishes to the broker (its one outbound channel is {@code
 * POST /der-events}), so this only computes and logs, per its own File-RBAC role.
 */
@Component
public class ComplianceTracker {

  private static final Logger LOG = LoggerFactory.getLogger(ComplianceTracker.class);
  private static final String TARGET_TOPIC =
      "sites/%s/devices/der_dispatch/measurements/target_active_power/watts";
  private static final String ACTUAL_TOPIC =
      "sites/%s/devices/der_dispatch/measurements/actual_active_power/watts";
  // Reason: MVP placeholder, not spec'd by anyone — percentage rather than an absolute watts
  // value (unlike der-control-api's own SHORTFALL_TOLERANCE_WATTS) since a utility's compliance
  // bands scale with the size of the request, not a fixed margin regardless of magnitude.
  private static final double TOLERANCE_FRACTION = 0.05;

  private final MqttClient mqtt;
  private final Config config;
  private final JsonMapper mapper;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  private final AtomicReference<@Nullable Double> lastTarget = new AtomicReference<>();
  private final AtomicReference<@Nullable Double> lastActual = new AtomicReference<>();

  public ComplianceTracker(MqttClient mqtt, Config config, JsonMapper mapper) {
    this.mqtt = mqtt;
    this.config = config;
    this.mapper = mapper;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    String targetTopic = TARGET_TOPIC.formatted(config.siteId());
    String actualTopic = ACTUAL_TOPIC.formatted(config.siteId());

    mqtt.subscribe(
        new MqttSubscription[] {
          new MqttSubscription(targetTopic, 1), new MqttSubscription(actualTopic, 1)
        },
        new IMqttMessageListener[] {
          (topic, message) -> guard(() -> handleTarget(message)),
          (topic, message) -> guard(() -> handleActual(message))
        });
  }

  /** No dispatch yet (or fully recovered) means nothing to be non-compliant with. */
  public boolean isCompliant() {
    Double target = lastTarget.get();
    Double actual = lastActual.get();
    if (target == null || actual == null) {
      return true;
    }
    double tolerance = Math.abs(target) * TOLERANCE_FRACTION;
    return Math.abs(target - actual) <= tolerance;
  }

  private void handleTarget(MqttMessage message) {
    lastTarget.set(mapper.readValue(message.getPayload(), FloatSample.class).value());
  }

  private void handleActual(MqttMessage message) {
    lastActual.set(mapper.readValue(message.getPayload(), FloatSample.class).value());
    if (LOG.isInfoEnabled() && !isCompliant()) {
      LOG.info(
          "compliance check failed: target {}W, actual {}W", lastTarget.get(), lastActual.get());
    }
  }

  private void guard(Runnable action) {
    executor.submit(
        () -> {
          try {
            action.run();
          } catch (RuntimeException e) {
            LOG.error("failed to process compliance input", e);
          }
        });
  }
}
