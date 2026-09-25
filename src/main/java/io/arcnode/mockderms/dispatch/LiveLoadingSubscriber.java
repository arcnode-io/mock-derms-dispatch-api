package io.arcnode.mockderms.dispatch;

import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
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

/**
 * Subscribes to dlr-tap-regulator-sim's provisional {@code test/line_loading/A} — a raw float
 * string, not the canonical {@code FloatSample} JSON wrapper. Deliberately simple, non-ADR topic
 * per Joe's own call: this is purely internal to {@code mock_derms}'s own plumbing (both this
 * service and dlr-tap-regulator-sim live inside the same trust domain per {@code ems/readme.md}'s
 * deployment diagram), no spec or cross-project consistency reason to force the ADR shape here.
 * Value is a deterministic mock sawtooth today, replaced with a real PZEM-004T reading once that
 * hardware lands — topic string stays the same either way.
 */
@Component
public class LiveLoadingSubscriber {

  private static final Logger LOG = LoggerFactory.getLogger(LiveLoadingSubscriber.class);
  private static final String TOPIC = "test/line_loading/A";

  private final MqttClient mqtt;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();
  private final AtomicReference<@Nullable Double> latestLoadingAmps = new AtomicReference<>();

  public LiveLoadingSubscriber(MqttClient mqtt) {
    this.mqtt = mqtt;
  }

  @PreDestroy
  void shutdown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(5, TimeUnit.SECONDS);
  }

  /** Subscribes on application startup, after {@link io.arcnode.mockderms.MqttConfig}. */
  @EventListener(ApplicationReadyEvent.class)
  public void subscribe() throws org.eclipse.paho.mqttv5.common.MqttException {
    // Reason: same Paho array-overload workaround as every other subscriber in this service — the
    // single-topic overload recurses and stack-overflows (eclipse-paho/paho.mqtt.java#917).
    mqtt.subscribe(
        new MqttSubscription[] {new MqttSubscription(TOPIC, 1)},
        new IMqttMessageListener[] {(t, message) -> guard(message)});
  }

  /** The latest known live loading (amps), or {@code null} before the first message arrives. */
  public @Nullable Double currentLoadingAmps() {
    return latestLoadingAmps.get();
  }

  private void guard(MqttMessage message) {
    executor.submit(
        () -> {
          try {
            latestLoadingAmps.set(
                Double.parseDouble(new String(message.getPayload(), StandardCharsets.UTF_8)));
          } catch (RuntimeException e) {
            LOG.error("failed to process line_loading message", e);
          }
        });
  }
}
