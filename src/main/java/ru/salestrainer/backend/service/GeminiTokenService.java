package ru.salestrainer.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.salestrainer.backend.controller.ApiException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues short-lived Gemini Live ephemeral tokens for the native client.
 *
 * The permanent Gemini API key never leaves the backend.  The token is
 * constrained to the selected model and system instruction using the
 * AuthToken.bidiGenerateContentSetup + fieldMask contract supported by the
 * v1beta AuthToken API and BidiGenerateContentConstrained WebSocket endpoint.
 *
 * IMPORTANT: newSessionExpireTime is intentionally NOT sent.  Google assigns
 * its documented provider-side default (60 seconds from token creation).  This
 * removes application-server clock skew from the most time-sensitive token
 * deadline and prevents an otherwise valid freshly-issued token from being
 * rejected with "new_session_expire_time deadline exceeded".
 */
@Service
public class GeminiTokenService {
    private static final Logger log = LoggerFactory.getLogger(GeminiTokenService.class);
    private static final long GOOGLE_DEFAULT_NEW_SESSION_SECONDS = 60L;

    private final RestClient restClient;
    private final String tokenUrl;
    private final String websocketUrl;
    private final long tokenExpireMinutes;

    public GeminiTokenService(RestClient.Builder builder,
                              @Value("${trainer.gemini.auth-token-url}") String tokenUrl,
                              @Value("${trainer.gemini.websocket-url}") String websocketUrl,
                              @Value("${trainer.gemini.token-expire-minutes:60}") long tokenExpireMinutes,
                              @Value("${trainer.gemini.http-timeout-seconds:30}") long httpTimeoutSeconds) {
        Duration timeout = Duration.ofSeconds(Math.max(3, httpTimeoutSeconds));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = builder.requestFactory(requestFactory).build();
        this.tokenUrl = tokenUrl;
        this.websocketUrl = websocketUrl;
        this.tokenExpireMinutes = Math.max(5, Math.min(240, tokenExpireMinutes));
    }

    @SuppressWarnings("unchecked")
    public TokenResult createConstrainedToken(String apiKey, String model, String systemInstruction) {
        String normalizedModel = normalizeModel(model);
        Instant requestedAt = Instant.now();
        Instant expiresAt = requestedAt.plus(tokenExpireMinutes, ChronoUnit.MINUTES);

        Map<String, Object> setup = new LinkedHashMap<>();
        setup.put("model", "models/" + normalizedModel);

        List<String> lockedFields = new ArrayList<>(List.of("model"));
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            setup.put("systemInstruction", Map.of(
                    "parts", List.of(Map.of("text", systemInstruction))));
            lockedFields.add("systemInstruction");
        }

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uses", 1);

        // expireTime is deliberately generous and is not the deadline for
        // opening the first session.  It limits how long this ephemeral token
        // can authorize messages/resumed connections.
        request.put("expireTime", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());

        // Do NOT send newSessionExpireTime here.  The official AuthToken API
        // defaults it to 60 seconds from token creation on Google's clock.
        request.put("bidiGenerateContentSetup", setup);
        request.put("fieldMask", String.join(",", lockedFields));

        try {
            Map<String, Object> result = restClient.post()
                    .uri(tokenUrl)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(Map.class);

            String token = result == null ? null : String.valueOf(result.getOrDefault("name", ""));
            if (token == null || token.isBlank() || "null".equals(token)) {
                throw ApiException.unavailable(
                        "GEMINI_TOKEN_INVALID",
                        "Gemini не вернул ephemeral token.");
            }

            Instant issuedAt = Instant.now();
            Instant newSessionExpiresAt = issuedAt.plusSeconds(GOOGLE_DEFAULT_NEW_SESSION_SECONDS);

            log.info(
                    "Gemini Live ephemeral token issued: model={}, fieldMask={}, tokenLifetimeMinutes={}, " +
                            "newSessionDeadline=google-default-60s, provisioningMs={}",
                    normalizedModel,
                    String.join(",", lockedFields),
                    tokenExpireMinutes,
                    Duration.between(requestedAt, issuedAt).toMillis());

            return new TokenResult(
                    token,
                    expiresAt,
                    newSessionExpiresAt,
                    websocketUrl,
                    normalizedModel);
        } catch (RestClientResponseException ex) {
            String body = ex.getResponseBodyAsString();
            String compact = body == null || body.isBlank() ? ex.getStatusText() : body;
            if (compact.length() > 1600) compact = compact.substring(0, 1600);

            log.warn("Gemini ephemeral token request failed: status={}, model={}, body={}",
                    ex.getStatusCode().value(), normalizedModel, compact);

            throw ApiException.unavailable(
                    "GEMINI_TOKEN_ERROR",
                    "Gemini отклонил создание ephemeral token (HTTP "
                            + ex.getStatusCode().value() + "): " + compact);
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Gemini ephemeral token provisioning failed: model={}, error={}",
                    normalizedModel, rootMessage(ex), ex);
            throw ApiException.unavailable(
                    "GEMINI_UNAVAILABLE",
                    "Не удалось связаться с Gemini: " + rootMessage(ex));
        }
    }

    private String normalizeModel(String model) {
        String value = model == null ? "" : model.trim();
        if (value.isBlank()) {
            throw ApiException.badRequest("GEMINI_MODEL_REQUIRED", "Не задана Gemini Live model.");
        }
        return value.startsWith("models/") ? value.substring("models/".length()) : value;
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    public record TokenResult(String ephemeralToken,
                              Instant expiresAt,
                              Instant newSessionExpiresAt,
                              String websocketUrl,
                              String model) {}
}
