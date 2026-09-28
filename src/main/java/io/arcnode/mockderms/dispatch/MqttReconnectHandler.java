package io.arcnode.mockderms.dispatch;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Re-establishes this service's MQTT subscriptions after a broker restart.
 *
 * <p>A subscription is broker-side session state, not client-side. The {@code MqttSubscription} and
 * listener held here are only a routing table for inbound messages — they do not make the broker
 * send anything. Paho's {@code automaticReconnect} restores the socket but never re-sends
 * SUBSCRIBE, and {@code cleanStart} defaults to true, so the reconnect asks for a fresh session
 * with no subscriptions in it.
 *
 * <p>Without this, a broker restart leaves {@link EventOrchestrator} without a DLR rating or line
 * loading. It holds until it has both, so it would tick and return forever: no trigger, no
 * envelope, no event, no error, and a passing health check. Confirmed against a real HiveMQ
 * container restart.
 *
 * <p>The subscribers are listed explicitly rather than collected through an interface, so what gets
 * re-established is readable in one place. A new subscriber has to be added here too.
 */
@Component
public class MqttReconnectHandler implements MqttCallback {

  private final MqttClient mqtt;

  private static final Logger LOG = LoggerFactory.getLogger(MqttReconnectHandler.class);

  private final DlrRatingSubscriber ratingSubscriber;
  private final LiveLoadingSubscriber loadingSubscriber;

  public MqttReconnectHandler(
      MqttClient mqtt,
      DlrRatingSubscriber ratingSubscriber,
      LiveLoadingSubscriber loadingSubscriber) {
    this.mqtt = mqtt;
    this.ratingSubscriber = ratingSubscriber;
    this.loadingSubscriber = loadingSubscriber;
  }

  /**
   * Installs this handler. Takes no argument: an {@code @EventListener} method's only parameter is
   * the event itself, so injecting the client here would silently never run. Per-topic listeners
   * registered by {@code subscribe} keep receiving their own messages, so taking the callback slot
   * does not divert message delivery.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void install() {
    mqtt.setCallback(this);
  }

  @Override
  public void connectComplete(boolean reconnect, String serverUri) {
    if (!reconnect) {
      return;
    }
    LOG.warn("🔌 Reconnected to {} — re-establishing subscriptions", serverUri);
    resubscribe("DLR rating", ratingSubscriber::subscribe);
    resubscribe("line loading", loadingSubscriber::subscribe);
  }

  /** One failure must not leave the remaining subscriptions unrestored. */
  private void resubscribe(String what, Resubscribe action) {
    try {
      action.run();
      LOG.info("🔌 Resubscribed: {}", what);
    } catch (MqttException e) {
      LOG.error("🔌 Could not resubscribe {} — this service is deaf on it until restart", what, e);
    }
  }

  @FunctionalInterface
  private interface Resubscribe {
    void run() throws MqttException;
  }

  @Override
  public void disconnected(MqttDisconnectResponse response) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("🔌 Broker connection lost: {}", response.getReasonString());
    }
  }

  @Override
  public void mqttErrorOccurred(MqttException exception) {
    if (LOG.isWarnEnabled()) {
      LOG.warn("🔌 MQTT error: {}", exception.getMessage());
    }
  }

  @Override
  public void messageArrived(String topic, MqttMessage message) {
    // Per-topic listeners registered at subscribe time handle delivery; nothing routes here.
  }

  @Override
  public void deliveryComplete(IMqttToken token) {
    // Publishes are fire-and-forget at QoS 0.
  }

  @Override
  public void authPacketArrived(int reasonCode, MqttProperties properties) {
    // Enhanced authentication isn't used.
  }
}
