package io.arcnode.mockderms.dispatch;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit — an MQTT subscription is broker-side session state. Paho reconnects the socket but never
 * re-sends SUBSCRIBE, and cleanStart defaults to true, so after a broker restart this service would
 * see neither a DLR rating nor line loading again. The orchestrator holds until it has both, so it
 * would tick and return forever: no trigger, no envelope, no event, and no error. AAA.
 */
@ExtendWith(MockitoExtension.class)
class MqttReconnectHandlerTest {

  @Mock private org.eclipse.paho.mqttv5.client.MqttClient mqtt;
  @Mock private DlrRatingSubscriber ratingSubscriber;
  @Mock private LiveLoadingSubscriber loadingSubscriber;

  private MqttReconnectHandler handler() {
    return new MqttReconnectHandler(mqtt, ratingSubscriber, loadingSubscriber);
  }

  @Test
  void resubscribesBothOrchestratorInputsAfterAReconnect() throws Exception {
    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert
    verify(ratingSubscriber).subscribe();
    verify(loadingSubscriber).subscribe();
  }

  @Test
  void doesNothingOnTheFirstConnect() throws Exception {
    // Arrange: ApplicationReadyEvent already subscribes at boot
    handler().connectComplete(false, "tcp://broker:1883");

    // Assert
    verify(ratingSubscriber, never()).subscribe();
    verify(loadingSubscriber, never()).subscribe();
  }

  @Test
  void aFailedResubscribeDoesNotStopTheOther() throws Exception {
    // Arrange
    org.mockito.BDDMockito.willThrow(new org.eclipse.paho.mqttv5.common.MqttException(0))
        .given(ratingSubscriber)
        .subscribe();

    // Act
    handler().connectComplete(true, "tcp://broker:1883");

    // Assert
    verify(loadingSubscriber).subscribe();
  }

  @Test
  void installItselfAsTheClientsCallback() {
    // Arrange: an @EventListener method's only parameter is the event, so taking the client as an
    // argument here would silently never run and nothing would ever be resubscribed
    MqttReconnectHandler handler = handler();

    // Act
    handler.install();

    // Assert
    verify(mqtt).setCallback(handler);
  }
}
