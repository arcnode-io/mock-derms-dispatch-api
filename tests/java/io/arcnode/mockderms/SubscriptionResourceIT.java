package io.arcnode.mockderms;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The Subscription resource over real HTTP. A 2030.5 client registers where it wants Notifications
 * delivered, and the subscription then has to be readable back — a Notification names it in its
 * subscriptionURI, so that URI has to resolve to something rather than to a resource nobody serves.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class SubscriptionResourceIT extends AbstractBrokerIT {

  private static final String SEP_XML = "application/sep+xml";

  private static final String SUBSCRIPTION =
      """
      <?xml version="1.0" encoding="UTF-8" standalone="yes"?>\
      <Subscription schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
      <subscribedResource>https://utility.invalid/derp/1/derc</subscribedResource>\
      <encoding>0</encoding><level>+S2</level><limit>1</limit>\
      <notificationURI>https://site.invalid/der-events</notificationURI></Subscription>\
      """;

  @LocalServerPort int port;
  RestTestClient rest;

  @BeforeEach
  void bindClient() {
    rest = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void registeringReturnsTheCreatedSubscriptionsLocation() {
    // Act / Assert
    rest.post()
        .uri("/sub")
        .contentType(MediaType.parseMediaType(SEP_XML))
        .body(SUBSCRIPTION)
        .exchange()
        .expectStatus()
        .isCreated()
        .expectHeader()
        .value("Location", location -> assertThat(location).contains("/sub/"));
  }

  @Test
  void aRegisteredSubscriptionReadsBackAsIeee20305Xml() {
    // Arrange
    rest.post()
        .uri("/sub")
        .contentType(MediaType.parseMediaType(SEP_XML))
        .body(SUBSCRIPTION)
        .exchange()
        .expectStatus()
        .isCreated();

    // Act / Assert: the notificationURI survives the round trip, since that is what the utility
    // pushes to
    rest.get()
        .uri("/sub/1")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .value(
            body ->
                assertThat(body)
                    .contains("<notificationURI>https://site.invalid/der-events</notificationURI>")
                    .contains("urn:ieee:std:2030.5:ns"));
  }

  @Test
  void anUnknownSubscriptionIdIsNotFound() {
    // Act / Assert
    rest.get().uri("/sub/999").exchange().expectStatus().isNotFound();
  }

  @Test
  void aSubscriptionWithoutANotificationUriIsRejected() {
    // Arrange: without it the utility has no destination, so accepting it would be accepting a
    // subscription it can never honour
    String noDestination =
        """
        <Subscription schemaVer="2.2" xmlns="urn:ieee:std:2030.5:ns">\
        <subscribedResource>https://utility.invalid/derp/1/derc</subscribedResource>\
        <encoding>0</encoding><level>+S2</level><limit>1</limit></Subscription>\
        """;

    // Act / Assert
    rest.post()
        .uri("/sub")
        .contentType(MediaType.parseMediaType(SEP_XML))
        .body(noDestination)
        .exchange()
        .expectStatus()
        .isBadRequest();
  }
}
