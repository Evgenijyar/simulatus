package ru.salestrainer.backend.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import ru.salestrainer.backend.security.ClientAuthInterceptor;
import ru.salestrainer.backend.service.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/client")
public class ClientApiController {
    private final ClientBootstrapService bootstrap; private final LiveSessionService sessions;
    public ClientApiController(ClientBootstrapService bootstrap,LiveSessionService sessions){this.bootstrap=bootstrap;this.sessions=sessions;}
    @GetMapping("/bootstrap") public ClientBootstrapService.Bootstrap bootstrap(HttpServletRequest req,@RequestHeader(value="X-Trainer-Client-Version",required=false)String version){ClientAuthService.AuthenticatedClient c=client(req);return bootstrap.bootstrap(c.userId(),version);}
    @PostMapping("/training-sessions") public LiveSessionService.SessionDescriptor start(@Valid @RequestBody StartRequest body,HttpServletRequest req){ClientAuthService.AuthenticatedClient c=client(req);ClientBootstrapService.Bootstrap b=bootstrap.bootstrap(c.userId(),body.clientVersion());if(b.version().updateRequired())throw ApiException.conflict("CLIENT_UPDATE_REQUIRED","Версия приложения больше не поддерживается. Обновите клиент тренажёра.");return sessions.start(c.userId(),c.deviceId(),body.roleId(),body.deviceId(),body.clientVersion());}
    @PostMapping("/training-sessions/{id}/heartbeat") public java.util.Map<String,Object> heartbeat(@PathVariable UUID id,HttpServletRequest req){ClientAuthService.AuthenticatedClient c=client(req);sessions.heartbeat(c.userId(),id);return java.util.Map.of("ok",true);}
    @PostMapping("/training-sessions/{id}/finish") public LiveSessionService.TrainingResult finish(@PathVariable UUID id,@RequestBody FinishRequest body,HttpServletRequest req){ClientAuthService.AuthenticatedClient c=client(req);return sessions.finish(c.userId(),id,body.transcript(),body.completionSource());}
    @PostMapping("/training-sessions/{id}/abandon") public java.util.Map<String,Object> abandon(@PathVariable UUID id,@RequestBody(required=false) AbandonRequest body,HttpServletRequest req){ClientAuthService.AuthenticatedClient c=client(req);sessions.abandon(c.userId(),id,body==null?"":body.transcript(),body==null?"Прервано клиентом":body.reason());return java.util.Map.of("ok",true);}
    private ClientAuthService.AuthenticatedClient client(HttpServletRequest req){Object v=req.getAttribute(ClientAuthInterceptor.CLIENT);if(v instanceof ClientAuthService.AuthenticatedClient c)return c;throw ApiException.unauthorized("Клиент не авторизован.");}
    public record StartRequest(@NotNull(message="Выберите роль.")Long roleId,String deviceId,String clientVersion){} public record FinishRequest(String transcript,String completionSource){} public record AbandonRequest(String transcript,String reason){}
}
