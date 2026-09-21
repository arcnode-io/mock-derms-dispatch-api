package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import io.arcnode.mockderms.dispatch.dto.DerEventRequest;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Calls ems-der-control-api's real {@code POST /der-events} — the actual utility-facing IEEE 2030.5
 * intake this whole mock exists to exercise. Presents this service's own identity via {@link
 * MockUtilityIdentity} rather than proving possession of a private key, matching how
 * der-control-api's own {@code ClientIdentity} treats the header (it never does a trust check
 * itself — that's the real gateway's job in production).
 */
@Service
public class DerEventsClient {

  private final RestClient client;

  public DerEventsClient(RestClient.Builder builder, Config config) {
    // Reason: the JDK HttpClient-backed default request factory hit a real, reproducible
    // EOFException from its own Http2Connection code against WireMock — confirmed across two
    // separate clean `mvn verify` runs (pass, then fail, no code change in between), so this
    // isn't a one-off. Neither WireMock nor der-control-api's own Tomcat has any HTTP/2 to
    // negotiate anyway. SimpleClientHttpRequestFactory is backed by HttpURLConnection, which
    // cannot speak HTTP/2 at all — verified reliable across repeated clean-recompiled runs.
    this.client =
        builder
            .requestFactory(new SimpleClientHttpRequestFactory())
            .baseUrl(config.derControlApiUrl())
            .build();
  }

  /** Constraint dispatch (trigger fired) or event close (posting event_active=false). */
  public void dispatch(DerEventRequest request) {
    client
        .post()
        .uri("/der-events")
        .header("X-SSL-Client-Cert", MockUtilityIdentity.HEADER_VALUE)
        .body(request)
        .retrieve()
        .toBodilessEntity();
  }
}
