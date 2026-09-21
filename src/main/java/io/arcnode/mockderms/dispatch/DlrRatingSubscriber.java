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
 * Subscribes to {@code line_rating}'s {@code dynamic_line_rating} (amps, IEEE 738 ampacity) and
 * holds the latest reading. Canonical contract confirmed directly with embedded-engineer — {@code
 * sites/{site}/devices/line_rating/measurements/dynamic_line_rating/amps}, {@code FloatSample},
 * retained — built ahead of the real publisher landing, same as every other subscriber in this
 * system: the contract is fixed, the implementation on the other end can lag.
 */
@Component
public class DlrRatingSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(DlrRatingSubscriber.class);
  private static final String TOPIC =
      "sites/%s/devices/line_rating/measurements/dynamic_line_rating/amps";

  private final MqttClient mqtt;
  private final Config config;
  private final JsonMapper mapper;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicReference<@Nullable Double> latestRatingAmps = new AtomicReference<>();

  public DlrRatingSubscriber(MqttClient mqtt, Config config, JsonMapper mapper) {
    this.mqtt = mqtt;
    this.config = config;
    this.mapper = mapper;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Subscribes on application startup, after {@link io.arcnode.mockderms.MqttConfig}. */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    String topic = TOPIC.formatted(config.siteId());

    // Reason: the single-topic MqttClient.subscribe(String, int, IMqttMessageListener) overload
    // recurses into itself and stack-overflows — a confirmed Paho 1.2.5 bug
    // (eclipse-paho/paho.mqtt.java#917/#863/#816). The array overload it's supposed to delegate
    // to is fine, even for one topic.
    mqtt.subscribe(
        new MqttSubscription[] {new MqttSubscription(topic, 1)},
        new IMqttMessageListener[] {(t, message) -> guard(message)});
  }

  /** The latest known rating in amps, or {@code null} before the first message arrives. */
  public @Nullable Double currentRatingAmps() {
    return latestRatingAmps.get();
  }

  private void guard(MqttMessage message) {
    executor.submit(
        () -> {
          try {
            latestRatingAmps.set(mapper.readValue(message.getPayload(), FloatSample.class).value());
          } catch (RuntimeException e) {
            LOG.error("failed to process dynamic_line_rating message", e);
          }
        });
  }
}
