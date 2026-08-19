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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiTokenService {
    private static final Logger log = LoggerFactory.getLogger(GeminiTokenService.class);

    private final RestClient restClient;
    private final String tokenUrl;
    private final String modelsUrl;
    private final String websocketUrl;
    private final long tokenExpireMinutes;
    private final long newSessionExpireSeconds;

    public GeminiTokenService(
            RestClient.Builder builder,
            @Value("${trainer.gemini.auth-token-url}") String tokenUrl,
            @Value("${trainer.gemini.models-url}") String modelsUrl,
            @Value("${trainer.gemini.websocket-url}") String websocketUrl,
            @Value("${trainer.gemini.token-expire-minutes:60}") long tokenExpireMinutes,
            @Value("${trainer.gemini.new-session-expire-seconds:60}") long newSessionExpireSeconds,
            @Value("${trainer.gemini.http-timeout-seconds:30}") long httpTimeoutSeconds) {

        Duration timeout = Duration.ofSeconds(Math.max(3, httpTimeoutSeconds));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = builder.requestFactory(requestFactory).build();
        this.tokenUrl = tokenUrl;
        this.modelsUrl = modelsUrl;
        this.websocketUrl = websocketUrl;
        this.tokenExpireMinutes = Math.max(5, Math.min(240, tokenExpireMinutes));
        this.newSessionExpireSeconds = Math.max(10, Math.min(60, newSessionExpireSeconds));
    }

    /**
     * Verifies the long-lived key independently from Live token provisioning.
     */
    public void validateApiKey(String apiKey) {
        try {
            restClient.get()
                    .uri(modelsUrl)
                    .header("x-goog-api-key", apiKey)
                    .retrieve()
                    .body(Map.class);
        } catch (RestClientResponseException ex) {
            String responseBody = compact(ex.getResponseBodyAsString());
            log.warn("Gemini API key check failed: status={}, body={}",
                    ex.getStatusCode().value(), responseBody);
            throw ApiException.unavailable(
                    "GEMINI_API_KEY_ERROR",
                    "Gemini не принял API key (HTTP "
                            + ex.getStatusCode().value() + "): "
                            + fallback(responseBody, ex.getStatusText()));
        } catch (RuntimeException ex) {
            log.warn("Gemini API key check failed before response: error={}", ex.toString());
            throw ApiException.unavailable(
                    "GEMINI_UNAVAILABLE",
                    "Не удалось проверить Gemini API key: " + ex.getMessage());
        }
    }

    /**
     * Creates a short-lived token for a direct client-to-Gemini Live connection.
     *
     * Current Gemini Live ephemeral-token contract uses liveConnectConstraints.
     * We intentionally constrain only fields that must be stable for the whole
     * training session: model, AUDIO response modality and session-resumption
     * capability. The role prompt, transcription settings, context compression
     * and finish_training tool are supplied by the native client's setup message.
     *
     * sessionResumption must be authorized in the token itself; otherwise a
     * constrained Live session cannot reliably issue/use resumption handles.
     */
    @SuppressWarnings("unchecked")
    public TokenResult createConstrainedToken(String apiKey, String model, String systemInstruction) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(tokenExpireMinutes, ChronoUnit.MINUTES);
        Instant newSessionExpiresAt = now.plus(newSessionExpireSeconds, ChronoUnit.SECONDS);
        String normalizedModel = normalizeModel(model);

        Map<String, Object> liveConfig = new LinkedHashMap<>();
        liveConfig.put("responseModalities", List.of("AUDIO"));
        liveConfig.put("sessionResumption", Map.of());

        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("model", modelResourceName(normalizedModel));
        constraints.put("config", liveConfig);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("uses", 1);
        request.put("expireTime", expiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
        request.put("newSessionExpireTime", newSessionExpiresAt.truncatedTo(ChronoUnit.SECONDS).toString());
        request.put("liveConnectConstraints", constraints);

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

            log.info("Gemini Live ephemeral token issued: model={}, expiresAt={}, newSessionExpiresAt={}, resumptionAllowed=true",
                    normalizedModel, expiresAt, newSessionExpiresAt);

            return new TokenResult(
                    token,
                    expiresAt,
                    newSessionExpiresAt,
                    websocketUrl,
                    normalizedModel);

        } catch (RestClientResponseException ex) {
            String responseBody = compact(ex.getResponseBodyAsString());
            log.warn("Gemini ephemeral token request failed: status={}, model={}, body={}",
                    ex.getStatusCode().value(), normalizedModel, responseBody);
            throw ApiException.unavailable(
                    "GEMINI_TOKEN_ERROR",
                    "Gemini отклонил создание ephemeral token (HTTP "
                            + ex.getStatusCode().value() + "): "
                            + fallback(responseBody, ex.getStatusText()));
        } catch (ApiException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("Gemini ephemeral token request failed before response: model={}, error={}",
                    normalizedModel, ex.toString());
            throw ApiException.unavailable(
                    "GEMINI_UNAVAILABLE",
                    "Не удалось связаться с Gemini: " + ex.getMessage());
        }
    }

    private String normalizeModel(String model) {
        if (model == null) return "";
        String value = model.trim();
        return value.startsWith("models/") ? value.substring("models/".length()) : value;
    }

    private String modelResourceName(String model) {
        return "models/" + normalizeModel(model);
    }

    private String compact(String value) {
        if (value == null) return "";
        String result = value.replaceAll("\\s+", " ").trim();
        return result.length() > 1200 ? result.substring(0, 1200) : result;
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record TokenResult(
            String ephemeralToken,
            Instant expiresAt,
            Instant newSessionExpiresAt,
            String websocketUrl,
            String model) {
    }
}
