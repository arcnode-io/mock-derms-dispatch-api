package io.arcnode.mockderms.callapi;

import io.arcnode.mockderms.callapi.dto.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Outbound HTTP client — the {@code call-api.service.ts} analog. One synchronous {@link RestClient}
 * against a configurable base URL ({@code call-api.base-url}); tests point it at WireMock.
 */
@Service
public class CallApiService {

  private final RestClient client;

  public CallApiService(RestClient.Builder builder, @Value("${call-api.base-url}") String baseUrl) {
    this.client = builder.baseUrl(baseUrl).build();
  }

  /** GET {baseUrl}/get and return its {@code url} field. */
  public ApiResponse fetchUrl() {
    return client.get().uri("/get").retrieve().body(ApiResponse.class);
  }
}
