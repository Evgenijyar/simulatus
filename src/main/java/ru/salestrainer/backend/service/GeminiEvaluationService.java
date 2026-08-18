package ru.salestrainer.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.salestrainer.backend.controller.ApiException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class GeminiEvaluationService {

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String generateUrl;

    public GeminiEvaluationService(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${trainer.gemini.generate-url}") String generateUrl,
            @Value("${trainer.gemini.http-timeout-seconds:30}") long timeoutSeconds) {

        Duration timeout = Duration.ofSeconds(Math.max(5, timeoutSeconds));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);

        this.restClient = builder.requestFactory(requestFactory).build();
        this.mapper = mapper;
        this.generateUrl = generateUrl;
    }

    @SuppressWarnings("unchecked")
    public EvaluationResult evaluate(
            String apiKey,
            String model,
            String evaluationPrompt,
            String transcript) {

        String instruction = (evaluationPrompt == null || evaluationPrompt.isBlank()
                ? defaultPrompt()
                : evaluationPrompt.trim())
                + "\n\nВАЖНО: верни только JSON по заданной схеме, без markdown и пояснений.";

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("required", List.of("score", "summary"));
        schema.put("properties", Map.of(
                "score", Map.of(
                        "type", "integer",
                        "minimum", 0,
                        "maximum", 100),
                "summary", Map.of("type", "string"),
                "strengths", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string")),
                "improvements", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"))));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of(
                "parts", List.of(Map.of("text", instruction))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of(
                        "text", "ТРАНСКРИПЦИЯ ТРЕНИРОВКИ:\n" + (transcript == null ? "" : transcript))))));
        body.put("generationConfig", Map.of(
                "responseMimeType", "application/json",
                "responseJsonSchema", schema,
                "temperature", 0.2));

        try {
            Map<String, Object> response = restClient.post()
                    .uri(generateUrl, model)
                    .header("x-goog-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            JsonNode root = mapper.valueToTree(response);
            String text = root.path("candidates")
                    .path(0)
                    .path("content")
                    .path("parts")
                    .path(0)
                    .path("text")
                    .asString("")
                    .trim();

            if (text.isBlank()) {
                throw ApiException.unavailable(
                        "EVALUATION_EMPTY",
                        "Gemini не вернул оценку тренировки.");
            }

            JsonNode json = mapper.readTree(stripFence(text));
            int score = Math.max(0, Math.min(100, json.path("score").asInt(0)));
            String summary = json.path("summary").asString("").trim();

            if (summary.isBlank()) {
                summary = "Разбор сформирован без текстового резюме.";
            }

            return new EvaluationResult(
                    score,
                    summary,
                    mapper.writeValueAsString(json));

        } catch (RestClientResponseException ex) {
            String responseBody = ex.getResponseBodyAsString();
            throw ApiException.unavailable(
                    "EVALUATION_GEMINI_ERROR",
                    "Gemini отклонил оценку: "
                            + compact(responseBody == null ? ex.getStatusText() : responseBody));
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ApiException.unavailable(
                    "EVALUATION_ERROR",
                    "Не удалось разобрать оценку Gemini: " + ex.getMessage());
        }
    }

    private String stripFence(String source) {
        String value = source.trim();
        if (value.startsWith("```")) {
            int newline = value.indexOf('\n');
            if (newline >= 0) {
                value = value.substring(newline + 1);
            }
            if (value.endsWith("```")) {
                value = value.substring(0, value.length() - 3);
            }
        }
        return value.trim();
    }

    private String compact(String source) {
        String value = source == null ? "" : source.replaceAll("\\s+", " ").trim();
        return value.length() > 900 ? value.substring(0, 900) : value;
    }

    private String defaultPrompt() {
        return "Ты — строгий тренер по продажам. Проанализируй диалог менеджера с клиентом. "
                + "Оцени качество по шкале 0–100 с учётом выявления потребностей, вопросов, "
                + "аргументации, работы с возражениями, следующего шага и качества коммуникации. "
                + "summary — конкретный разбор на русском в 4–8 предложениях; strengths и improvements "
                + "— короткие практические пункты.";
    }

    public record EvaluationResult(int score, String summary, String json) {
    }
}
