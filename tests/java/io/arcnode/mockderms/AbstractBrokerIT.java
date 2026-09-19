package io.arcnode.mockderms;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.hivemq.HiveMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared HiveMQ broker for every {@code *IT} — {@code MqttConfig} connects on boot, so every
 * {@code @SpringBootTest} needs one reachable, and Spring's context cache means several {@code *IT}
 * classes reuse the SAME {@code ApplicationContext} (and its {@code MqttClient} bean) across a run.
 * Same pattern as ems-der-control-api's own {@code AbstractBrokerIT} — see its Javadoc for why this
 * is a static-initializer singleton container rather than a JUnit5-managed {@code @Container}
 * field.
 */
public abstract class AbstractBrokerIT {

  static final HiveMQContainer HIVEMQ;

  static {
    HIVEMQ = new HiveMQContainer(DockerImageName.parse("hivemq/hivemq-ce"));
    if (DockerClientFactory.instance().isDockerAvailable()) {
      HIVEMQ.start();
    }
  }

  @DynamicPropertySource
  static void mqttProperties(DynamicPropertyRegistry registry) {
    registry.add(
        "app.mqttBrokerUrl", () -> "tcp://" + HIVEMQ.getHost() + ":" + HIVEMQ.getMqttPort());
  }
}
