package io.arcnode.mockderms.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

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

/**
 * Unit — subscribes to dlr-tap-regulator-sim's provisional {@code test/line_loading/A} (raw float
 * string, not the canonical {@code FloatSample} JSON wrapper — a deliberately simple, non-ADR topic
 * per Joe's own call: this is purely internal to mock_derms's own plumbing, no spec or
 * cross-project consistency reason to force the ADR shape here). Replaces {@code
 * SyntheticLoadGenerator}'s internal RNG with a real (currently synthetic-valued) device publish.
 * Mocked broker, AAA.
 */
@ExtendWith(MockitoExtension.class)
class LiveLoadingSubscriberTest {

  private static final String TOPIC = "test/line_loading/A";

  @Mock private MqttClient mqtt;
  @Captor private ArgumentCaptor<MqttSubscription[]> subscriptions;
  @Captor private ArgumentCaptor<IMqttMessageListener[]> listeners;

  private LiveLoadingSubscriber subscriber() {
    return new LiveLoadingSubscriber(mqtt);
  }

  private static MqttMessage rawFloat(double value) {
    return new MqttMessage(Double.toString(value).getBytes());
  }

  @Test
  void hasNoReadingUntilFirstMessageArrives() {
    // Act / Assert
    assertThat(subscriber().currentLoadingAmps()).isNull();
  }

  @Test
  void subscribesToTheProvisionalLineLoadingTopicOnStartup() throws Exception {
    // Act
    subscriber().subscribe();

    // Assert
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    assertThat(subscriptions.getValue())
        .extracting(MqttSubscription::getTopic)
        .containsExactly(TOPIC);
  }

  @Test
  void holdsTheLatestReadingParsedFromARawFloatString() throws Exception {
    // Arrange
    LiveLoadingSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act
    listener.messageArrived(TOPIC, rawFloat(42.5));

    // Assert
    long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
    while (subscriber.currentLoadingAmps() == null && System.nanoTime() < deadline) {
      Thread.onSpinWait();
    }
    assertThat(subscriber.currentLoadingAmps()).isEqualTo(42.5);
  }

  @Test
  void aMalformedPayloadDoesNotPropagateFromTheListener() throws Exception {
    // Arrange
    LiveLoadingSubscriber subscriber = subscriber();
    subscriber.subscribe();
    org.mockito.Mockito.verify(mqtt).subscribe(subscriptions.capture(), listeners.capture());
    IMqttMessageListener listener = listeners.getValue()[0];

    // Act / Assert
    assertThatCode(() -> listener.messageArrived(TOPIC, new MqttMessage("not a float".getBytes())))
        .doesNotThrowAnyException();
  }
}
