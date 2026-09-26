package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

import io.arcnode.mockderms.Config;
import java.time.Duration;
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
 * Unit — subscribes to a dlr_rtu instance's dynamic_line_rating (canonical contract, now live on
 * real hardware) and holds the latest amps reading. Mocked broker, real JsonMapper, AAA.
 */
@ExtendWith(MockitoExtension.class)
class DlrRatingSubscriberTest {

  private static final String TOPIC =
      "sites/site_001/devices/dlr_rtu_demo/measurements/dynamic_line_rating/amps";

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
          "http://localhost:8081",
          13.8,
          50.0,
          4.0,
          "dlr_rtu_demo",
          "https://example.invalid/token",
          "https://example.invalid/archive",
          1800.0,
          25.0);
  private final JsonMapper mapper = JsonMapper.builder().build();

  @Mock private MqttClient mqtt;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private DlrRatingSubscriber subscriber() {
    return new DlrRatingSubscriber(mqtt, config, mapper);
  }

  private static MqttMessage sample(double value) {
    return new MqttMessage(
        ("{\"ts\":\"2026-09-20T00:00:00Z\",\"value\":" + value + "}").getBytes());
  }

  @Test
  void hasNoRatingUntilFirstMessageArrives() {
    // Act / Assert
    assertThat(subscriber().currentRatingAmps()).isNull();
  }

  @Test
  void subscribesToTheCanonicalRatingTopicOnStartup() throws Exception {
    // Act
    subscriber().subscribe();

    // Assert
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(TOPIC);
  }

  @Test
  void holdsTheLatestRatingAfterAMessageArrives() throws Exception {
    // Arrange
    DlrRatingSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act
    listener.messageArrived(TOPIC, sample(612.5));

    // Assert
    await()
        .atMost(Duration.ofSeconds(1))
        .untilAsserted(() -> assertThat(subscriber.currentRatingAmps()).isEqualTo(612.5));
  }

  @Test
  void aMalformedPayloadDoesNotPropagateFromTheListener() throws Exception {
    // Arrange
    DlrRatingSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act / Assert
    assertThatCode(() -> listener.messageArrived(TOPIC, new MqttMessage("not json".getBytes())))
        .doesNotThrowAnyException();
  }
}
