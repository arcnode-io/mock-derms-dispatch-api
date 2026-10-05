package io.arcnode.mockderms.dispatch;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.arcnode.mockderms.Config;
import java.time.Clock;
import java.time.Duration;
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
  // Reason: real tokens are valid 1h with no refresh; re-authenticate this much early so an
  // in-flight request never straddles expiry.
  private static final long REFRESH_MARGIN_SECONDS = 300;

  private final RestClient client;
  private final String username;
  private final String password;
  private final Clock clock;
  private final AtomicReference<@Nullable CachedToken> cached = new AtomicReference<>();

  private record CachedToken(String idToken, Instant expiresAt) {}

  // Reason: an unanswered ERCOT must fail, not hang. These calls run on the scheduled
  // control-loop thread, so a request that never returns does not merely lose the zone reading —
  // it starves the curtailment trigger sharing that thread, which stops evaluating entirely with
  // nothing logged and both containers healthy. SimpleClientHttpRequestFactory defaults to no
  // timeout at all, and a destination whose DNS resolves while its TCP connect blackholes hangs
  // rather than erroring. Bounded well under the tracker's 5-minute cache, so at worst one tick
  // per cache cycle stalls; absence then means "not stressed", which only ever removes the
  // margin boost and can never cause a dispatch on its own.
  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
  private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

  /** A request factory that gives up rather than blocking the caller's thread forever. */
  private static SimpleClientHttpRequestFactory boundedRequestFactory() {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(CONNECT_TIMEOUT);
    factory.setReadTimeout(READ_TIMEOUT);
    return factory;
  }

  public ErcotTokenClient(
      RestClient.Builder builder,
      Config config,
      @Value("${ERCOT_PASSWORD:}") String password,
      Clock clock) {
    this.client =
        builder.requestFactory(boundedRequestFactory()).baseUrl(config.ercotTokenUrl()).build();
    // Reason: whose ERCOT account this is differs per deployment, so it is config rather than
    // something compiled into the artifact every deployment shares. Not a secret — it is an
    // identifier, and the password it pairs with stays in the environment.
    this.username = config.ercotUsername();
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
    form.add("username", username);
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

  // Reason: real ERCOT response field is snake_case id_token — no global Jackson naming strategy
  // configured, so without this the field silently deserialized to null (confirmed via a real
  // isolated probe against the live endpoint with real credentials: auth succeeds, real token
  // comes back under "id_token", but idToken never matched it).
  private record TokenResponse(@JsonProperty("id_token") String idToken) {}
}
