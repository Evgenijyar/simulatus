package ru.salestrainer.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import ru.salestrainer.backend.controller.ApiException;
import ru.salestrainer.backend.model.*;
import ru.salestrainer.backend.repository.*;

import java.time.*;
import java.util.*;

@Service
public class LiveSessionService {
    private static final List<String> ACTIVE=List.of("PROVISIONING","ACTIVE","EVALUATING");
    private final AppUserRepository users; private final PromptProfileRepository roles; private final AiCredentialRepository credentials; private final LiveSessionRepository sessions;
    private final GeminiTokenService tokens; private final AiCredentialService credentialService; private final GeminiEvaluationService evaluator; private final TransactionTemplate tx; private final Duration lease;
    public LiveSessionService(AppUserRepository users,PromptProfileRepository roles,AiCredentialRepository credentials,LiveSessionRepository sessions,GeminiTokenService tokens,AiCredentialService credentialService,GeminiEvaluationService evaluator,TransactionTemplate tx,@Value("${trainer.live.lease-seconds:180}")long leaseSeconds){this.users=users;this.roles=roles;this.credentials=credentials;this.sessions=sessions;this.tokens=tokens;this.credentialService=credentialService;this.evaluator=evaluator;this.tx=tx;this.lease=Duration.ofSeconds(Math.max(60,leaseSeconds));}

    public SessionDescriptor start(Long userId,String authenticatedDeviceId,Long roleId,String requestDeviceId,String clientVersion){
        Reservation r=tx.execute(s->reserve(userId,authenticatedDeviceId,roleId,requestDeviceId,clientVersion));if(r==null)throw ApiException.unavailable("SESSION_RESERVE_FAILED","Не удалось зарезервировать тренировку.");
        try{
            String prompt=buildLivePrompt(r.role);String apiKey=credentialService.decrypt(r.credential);GeminiTokenService.TokenResult token=tokens.createConstrainedToken(apiKey,r.role.getModel(),prompt);
            tx.executeWithoutResult(s->{LiveSession entity=sessions.findById(r.sessionId).orElseThrow();entity.setStatus("ACTIVE");entity.setActivatedAt(Instant.now());entity.setTokenExpiresAt(token.expiresAt());entity.setLeaseExpiresAt(Instant.now().plus(lease));sessions.save(entity);});
            return new SessionDescriptor(r.sessionId,token.ephemeralToken(),token.expiresAt(),token.newSessionExpiresAt(),token.websocketUrl(),token.model(),prompt,r.role.getEvaluationPrompt(),r.role.getEvaluationModel());
        }catch(RuntimeException ex){tx.executeWithoutResult(s->closeInternal(r.sessionId,"FAILED","START_ERROR",trim(ex.getMessage(),450)));throw ex;}
    }

    public void heartbeat(Long userId,UUID id){tx.executeWithoutResult(s->{LiveSession e=requireOwned(userId,id);if(ACTIVE.contains(e.getStatus())){e.setLeaseExpiresAt(Instant.now().plus(lease));sessions.save(e);}});}

    public TrainingResult finish(Long userId,UUID id,String transcript,String completionSource){
        Snapshot snap=tx.execute(s->{LiveSession e=requireOwned(userId,id);if("COMPLETED".equals(e.getStatus()))return Snapshot.completed(e);if(!ACTIVE.contains(e.getStatus()))throw ApiException.conflict("SESSION_CLOSED","Тренировка уже завершена.");e.setStatus("EVALUATING");e.setTranscript(trim(transcript,200_000));e.setCompletionSource(trim(completionSource,40));e.setCloseReason("Логическое завершение тренировки");e.setLeaseExpiresAt(Instant.now().plus(Duration.ofMinutes(3)));sessions.save(e);return new Snapshot(e.getId(),e.getAiCredential().getId(),e.getPromptProfile().getEvaluationModel(),e.getPromptProfile().getEvaluationPrompt(),e.getTranscript(),null,null,null);});
        if(snap.score!=null)return new TrainingResult(snap.score,snap.summary,snap.json);
        try{
            AiCredential credential=credentials.findById(snap.credentialId).orElseThrow(()->ApiException.notFound("AI-ключ не найден."));GeminiEvaluationService.EvaluationResult result=evaluator.evaluate(credentialService.decrypt(credential),snap.evaluationModel,snap.evaluationPrompt,snap.transcript);
            tx.executeWithoutResult(s->{LiveSession e=sessions.findById(id).orElseThrow();e.setScore(result.score());e.setEvaluationSummary(result.summary());e.setEvaluationJson(result.json());e.setEvaluatedAt(Instant.now());e.setStatus("COMPLETED");e.setClosedAt(Instant.now());e.setLeaseExpiresAt(Instant.now());sessions.save(e);});
            return new TrainingResult(result.score(),result.summary(),result.json());
        }catch(RuntimeException ex){tx.executeWithoutResult(s->{LiveSession e=sessions.findById(id).orElseThrow();e.setStatus("EVALUATION_FAILED");e.setClosedAt(Instant.now());e.setLeaseExpiresAt(Instant.now());e.setCloseReason(trim("Ошибка оценки: "+ex.getMessage(),450));sessions.save(e);});throw ex;}
    }

    public void abandon(Long userId,UUID id,String transcript,String reason){tx.executeWithoutResult(s->{LiveSession e=requireOwned(userId,id);if(ACTIVE.contains(e.getStatus())){e.setTranscript(trim(transcript,200_000));e.setCompletionSource("CLIENT_ABORT");e.setStatus("ABORTED");e.setClosedAt(Instant.now());e.setLeaseExpiresAt(Instant.now());e.setCloseReason(trim(reason,450));sessions.save(e);}});}
    public void terminateByAdmin(UUID id){tx.executeWithoutResult(s->closeInternal(id,"TERMINATED","ADMIN","Остановлено администратором"));}
    public void terminateForUserByAdmin(Long userId){tx.executeWithoutResult(s->sessions.findByUser_IdAndStatusIn(userId,ACTIVE).forEach(e->closeInternal(e.getId(),"TERMINATED","ADMIN","Доступ пользователя отключён")));}
    @Scheduled(fixedDelayString="${trainer.live.cleanup-delay-ms:30000}") public void cleanup(){tx.executeWithoutResult(s->sessions.findByStatusInAndLeaseExpiresAtBefore(ACTIVE,Instant.now()).forEach(e->closeInternal(e.getId(),"EXPIRED","LEASE","Истёк heartbeat тренировки")));}

    private Reservation reserve(Long userId,String authDevice,Long roleId,String requestDevice,String clientVersion){AppUser user=users.lockById(userId).orElseThrow(()->ApiException.notFound("Пользователь не найден."));if(!user.isEnabled())throw ApiException.forbidden("Доступ пользователя отключён.");PromptProfile role=roles.findById(roleId).orElseThrow(()->ApiException.notFound("Роль не найдена."));if(!role.isEnabled()||user.getPromptProfiles().stream().noneMatch(x->x.getId().equals(roleId)))throw ApiException.forbidden("Эта роль недоступна пользователю.");String device=(requestDevice==null||requestDevice.isBlank())?authDevice:requestDevice.trim();if(!Objects.equals(device,authDevice))throw ApiException.forbidden("Неверный deviceId.");Instant now=Instant.now();AiCredential selected=null;for(AiCredential c:credentials.lockEnabledCredentials()){if(sessions.countLeasedForCredential(c.getId(),now)<c.getMaxConcurrentSessions()){selected=c;break;}}if(selected==null)throw ApiException.unavailable("NO_AI_CAPACITY","Нет свободного Gemini-ключа. Попробуйте позже.");LiveSession e=new LiveSession();e.setId(UUID.randomUUID());e.setUser(user);e.setPromptProfile(role);e.setAiCredential(selected);e.setStatus("PROVISIONING");e.setDeviceId(device);e.setClientVersion(trim(clientVersion,60));e.setPromptVersion(role.getVersion());e.setStartedAt(now);e.setLeaseExpiresAt(now.plus(lease));sessions.save(e);return new Reservation(e.getId(),role,selected);}
    private LiveSession requireOwned(Long userId,UUID id){return sessions.findByIdAndUser_Id(id,userId).orElseThrow(()->ApiException.notFound("Тренировка не найдена."));}
    private void closeInternal(UUID id,String status,String source,String reason){sessions.findById(id).ifPresent(e->{if(ACTIVE.contains(e.getStatus())){e.setStatus(status);e.setCompletionSource(source);e.setCloseReason(reason);e.setClosedAt(Instant.now());e.setLeaseExpiresAt(Instant.now());sessions.save(e);}});}
    private String buildLivePrompt(PromptProfile role){String base=role.getSystemPrompt()==null?"":role.getSystemPrompt().trim();String protocol="""
            Ты находишься в тренировочном звонке и играешь ТОЛЬКО роль клиента из сценария выше. Общайся естественно голосом, не подсказывай менеджеру и не оценивай его во время разговора.
            В сессии доступна функция finish_training. Вызови её ровно один раз только тогда, когда разговор логически завершён: стороны попрощались, договорились о следующем шаге/перезвоне, клиент окончательно отказался либо достигнут иной естественный финал. Не вызывай функцию посреди незавершённой беседы. Не произноси название функции и не объясняй механику тренажёра.
            """;return (base+"\n\n### ТЕХНИЧЕСКИЙ ПРОТОКОЛ ТРЕНАЖЁРА\n"+protocol).trim();}
    private String trim(String v,int max){if(v==null)return "";return v.length()<=max?v:v.substring(0,max);}
    private record Reservation(UUID sessionId,PromptProfile role,AiCredential credential){}
    private record Snapshot(UUID id,Long credentialId,String evaluationModel,String evaluationPrompt,String transcript,Integer score,String summary,String json){static Snapshot completed(LiveSession e){return new Snapshot(e.getId(),e.getAiCredential().getId(),e.getPromptProfile().getEvaluationModel(),e.getPromptProfile().getEvaluationPrompt(),e.getTranscript(),e.getScore(),e.getEvaluationSummary(),e.getEvaluationJson());}}
    public record SessionDescriptor(UUID sessionId,String ephemeralToken,Instant tokenExpiresAt,Instant newSessionExpiresAt,String websocketUrl,String model,String systemInstruction,String evaluationPrompt,String evaluationModel){}
    public record TrainingResult(int score,String summary,String evaluationJson){}
}
