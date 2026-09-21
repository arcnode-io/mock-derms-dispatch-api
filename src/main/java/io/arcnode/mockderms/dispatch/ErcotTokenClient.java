package io.arcnode.mockderms.dispatch;

import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * ERCOT Public API OAuth2 ROPC token acquisition — verified directly against the real endpoint (not
 * docs alone) using working prior art from {@code ~/fullstack-energy/analyst-api/model/src/
 * process.py}. {@code client_id}/{@code scope} are public per ERCOT's own docs, not secrets — only
 * the account password and subscription key are. Tokens expire in 1 hour with no refresh mechanism,
 * so this re-authenticates from scratch shortly before expiry rather than trying to refresh one.
 */
@Component
public class ErcotTokenClient {

  private static final String CLIENT_ID = "fec253ea-0d06-4272-a5e6-b478baeecd70";
  private static final String SCOPE = "openid fec253ea-0d06-4272-a5e6-b478baeecd70 offline_access";
  // Reason: this is the registered ERCOT account's username, not a secret — the password and
  // subscription key are what's actually sensitive, both in template-secrets.env.
  static final String USERNAME = "joe@arketyped.net";
  // Reason: real tokens are valid 1h with no refresh; re-authenticate this much early so an
  // in-flight request never straddles expiry.
  private static final long REFRESH_MARGIN_SECONDS = 300;

  private final RestClient client;
  private final String password;
  private final Clock clock;
  private final AtomicReference<@Nullable CachedToken> cached = new AtomicReference<>();

  private record CachedToken(String idToken, Instant expiresAt) {}

  public ErcotTokenClient(
      RestClient.Builder builder,
      Config config,
      @Value("${ERCOT_PASSWORD:}") String password,
      Clock clock) {
    this.client =
        builder
            .requestFactory(new SimpleClientHttpRequestFactory())
            .baseUrl(config.ercotTokenUrl())
            .build();
    this.password = password;
    this.clock = clock;
  }

  /** A currently-valid token, re-authenticating first if none is cached or it's about to expire. */
  public String currentToken() {
    Instant now = clock.instant();
    CachedToken token = cached.get();
    if (token != null && now.isBefore(token.expiresAt())) {
      return token.idToken();
    }
    return authenticate(now);
  }

  private String authenticate(Instant now) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("username", USERNAME);
    form.add("password", password);
    form.add("grant_type", "password");
    form.add("scope", SCOPE);
    form.add("client_id", CLIENT_ID);
    form.add("response_type", "id_token");

    TokenResponse response =
        client
            .post()
            .contentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(TokenResponse.class);
    if (response == null) {
      throw new IllegalStateException("ERCOT token endpoint returned an empty response");
    }
    Instant expiresAt = now.plusSeconds(3600 - REFRESH_MARGIN_SECONDS);
    cached.set(new CachedToken(response.idToken(), expiresAt));
    return response.idToken();
  }

  private record TokenResponse(String idToken) {}
}
