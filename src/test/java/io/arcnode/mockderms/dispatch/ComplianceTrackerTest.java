package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import io.arcnode.mockderms.Config;
import org.eclipse.paho.mqttv5.client.IMqttMessageListener;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit — arm's-length compliance verification: an independent comparison of what der-control-api
 * says it dispatched against the site's own actual delivery, rather than trusting der-control-api's
 * self-reported dispatch_shortfall/dispatch_overdelivery. This is a real 2030.5 utility posture,
 * not a duplicate of der-control-api's own comparison. AAA.
 */
@ExtendWith(MockitoExtension.class)
class ComplianceTrackerTest {

  private static final String TARGET_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/target_active_power/watts";
  private static final String ACTUAL_TOPIC =
      "sites/site_001/devices/der_dispatch/measurements/actual_active_power/watts";

  private final Config config =
      new Config(
          Config.LogLevel.INFO,
          8080,
          "localhost",
          false,
          "tcp://localhost:1883",
          "arcnode_mock_derms_dispatch_api",
          "site_001",
          "http://localhost:8080",
          13.8,
          50.0,
          4.0);
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Mock private MqttClient mqtt;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private ComplianceTracker tracker() {
    return new ComplianceTracker(mqtt, config, mapper);
  }

  private static MqttMessage sample(double value) {
    return new MqttMessage(
        ("{\"ts\":\"2026-09-20T00:00:00Z\",\"value\":" + value + "}").getBytes());
  }

  @Test
  void subscribesToTargetAndActualActivePowerOnStartup() throws Exception {
    // Act
    tracker().subscribe();

    // Assert
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(TARGET_TOPIC, ACTUAL_TOPIC);
  }

  @Test
  void isCompliantWhenActualIsWithinToleranceOfTarget() throws Exception {
    // Arrange
    ComplianceTracker tracker = tracker();
    tracker.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener actualListener = listeners.getValue()[1];

    // Act
    targetListener.messageArrived(TARGET_TOPIC, sample(100_000.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(99_800.0));

    // Assert
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.isCompliant()).isTrue());
  }

  @Test
  void isNotCompliantWhenActualIsFarFromTarget() throws Exception {
    // Arrange
    ComplianceTracker tracker = tracker();
    tracker.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener targetListener = listeners.getValue()[0];
    IMqttMessageListener actualListener = listeners.getValue()[1];

    // Act
    targetListener.messageArrived(TARGET_TOPIC, sample(100_000.0));
    actualListener.messageArrived(ACTUAL_TOPIC, sample(20_000.0));

    // Assert
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(tracker.isCompliant()).isFalse());
  }

  @Test
  void isCompliantByDefaultBeforeAnyMeasurementsArrive() {
    // Act / Assert: no target dispatched yet — nothing to be non-compliant with
    assertThat(tracker().isCompliant()).isTrue();
  }

  @Test
  void aMalformedPayloadDoesNotPropagateFromTheListener() throws Exception {
    // Arrange
    ComplianceTracker tracker = tracker();
    tracker.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener actualListener = listeners.getValue()[1];

    // Act / Assert
    org.assertj.core.api.Assertions.assertThatCode(
            () ->
                actualListener.messageArrived(ACTUAL_TOPIC, new MqttMessage("not json".getBytes())))
        .doesNotThrowAnyException();
  }
}
