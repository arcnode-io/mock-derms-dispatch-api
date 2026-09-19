package io.arcnode.mockderms;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Connects to the deployment broker on boot as the {@code arcnode_mock_derms_dispatch_api}
 * File-RBAC identity — same pattern as ems-der-control-api's own {@code MqttConfig}. Synchronous
 * connect: if the broker isn't reachable at startup, the app fails to start rather than silently
 * running with no rating/compliance feed.
 */
@Configuration
public class MqttConfig {

  @Bean(destroyMethod = "close")
  public MqttClient mqttClient(
      Config config, @Value("${MQTT_MOCK_DERMS_DISPATCH_API_PASSWORD:}") String password)
      throws org.eclipse.paho.mqttv5.common.MqttException {
    String clientId = config.mqttUsername() + "-" + UUID.randomUUID();
    MqttClient client = new MqttClient(config.mqttBrokerUrl(), clientId, new MemoryPersistence());

    MqttConnectionOptions options = new MqttConnectionOptions();
    options.setUserName(config.mqttUsername());
    options.setPassword(password.getBytes(StandardCharsets.UTF_8));
    options.setAutomaticReconnect(true);
    client.connect(options);
    return client;
  }
}
