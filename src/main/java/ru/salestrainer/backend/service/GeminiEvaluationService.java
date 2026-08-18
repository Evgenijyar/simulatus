package ru.salestrainer.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import ru.salestrainer.backend.controller.ApiException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

@Service
public class GeminiEvaluationService {
    private final RestClient restClient; private final ObjectMapper mapper; private final String generateUrl;
    public GeminiEvaluationService(RestClient.Builder builder,ObjectMapper mapper,@Value("${trainer.gemini.generate-url}")String generateUrl,@Value("${trainer.gemini.http-timeout-seconds:30}")long timeoutSeconds){
        Duration t=Duration.ofSeconds(Math.max(5,timeoutSeconds));HttpClient hc=HttpClient.newBuilder().connectTimeout(t).build();JdkClientHttpRequestFactory rf=new JdkClientHttpRequestFactory(hc);rf.setReadTimeout(t);this.restClient=builder.requestFactory(rf).build();this.mapper=mapper;this.generateUrl=generateUrl;
    }
    @SuppressWarnings("unchecked")
    public EvaluationResult evaluate(String apiKey,String model,String evaluationPrompt,String transcript){
        String instruction=(evaluationPrompt==null||evaluationPrompt.isBlank()?defaultPrompt():evaluationPrompt.trim())+"\n\nВАЖНО: верни только JSON по заданной схеме, без markdown и пояснений.";
        Map<String,Object> schema=new LinkedHashMap<>();
        schema.put("type","object");
        schema.put("required",List.of("score","summary"));
        schema.put("properties",Map.of(
                "score",Map.of("type","integer","minimum",0,"maximum",100),
                "summary",Map.of("type","string"),
                "strengths",Map.of("type","array","items",Map.of("type","string")),
                "improvements",Map.of("type","array","items",Map.of("type","string"))));
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("systemInstruction",Map.of("parts",List.of(Map.of("text",instruction))));
        body.put("contents",List.of(Map.of("role","user","parts",List.of(Map.of("text","ТРАНСКРИПЦИЯ ТРЕНИРОВКИ:\n"+(transcript==null?"":transcript))))));
        body.put("generationConfig",Map.of("responseMimeType","application/json","responseJsonSchema",schema,"temperature",0.2));
        try{
            Map<String,Object> response=restClient.post().uri(generateUrl,model).header("x-goog-api-key",apiKey).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(Map.class);
            JsonNode root=mapper.valueToTree(response);String text=root.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("").trim();
            if(text.isBlank())throw ApiException.unavailable("EVALUATION_EMPTY","Gemini не вернул оценку тренировки.");
            JsonNode json=mapper.readTree(stripFence(text));int score=Math.max(0,Math.min(100,json.path("score").asInt(0)));String summary=json.path("summary").asText("").trim();
            if(summary.isBlank())summary="Разбор сформирован без текстового резюме.";
            return new EvaluationResult(score,summary,mapper.writeValueAsString(json));
        }catch(RestClientResponseException ex){String b=ex.getResponseBodyAsString();throw ApiException.unavailable("EVALUATION_GEMINI_ERROR","Gemini отклонил оценку: "+compact(b==null?ex.getStatusText():b));}
        catch(ApiException ex){throw ex;}catch(Exception ex){throw ApiException.unavailable("EVALUATION_ERROR","Не удалось разобрать оценку Gemini: "+ex.getMessage());}
    }
    private String stripFence(String s){String x=s.trim();if(x.startsWith("```")){int nl=x.indexOf('\n');if(nl>=0)x=x.substring(nl+1);if(x.endsWith("```"))x=x.substring(0,x.length()-3);}return x.trim();}
    private String compact(String s){s=s==null?"":s.replaceAll("\\s+"," ").trim();return s.length()>900?s.substring(0,900):s;}
    private String defaultPrompt(){return "Ты — строгий тренер по продажам. Проанализируй диалог менеджера с клиентом. Оцени качество по шкале 0–100 с учётом выявления потребностей, вопросов, аргументации, работы с возражениями, следующего шага и качества коммуникации. summary — конкретный разбор на русском в 4–8 предложениях; strengths и improvements — короткие практические пункты.";}
    public record EvaluationResult(int score,String summary,String json){}
}
