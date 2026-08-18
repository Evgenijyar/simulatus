package ru.salestrainer.backend.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;
import ru.salestrainer.backend.model.*;
import ru.salestrainer.backend.service.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
public class AdminApiController {
    private final BackofficeQueryService query;private final UserService users;private final PromptProfileService roles;private final AiCredentialService credentials;private final LiveSessionService sessions;private final SystemConfigService system;private final AuditService audit;
    public AdminApiController(BackofficeQueryService query,UserService users,PromptProfileService roles,AiCredentialService credentials,LiveSessionService sessions,SystemConfigService system,AuditService audit){this.query=query;this.users=users;this.roles=roles;this.credentials=credentials;this.sessions=sessions;this.system=system;this.audit=audit;}
    @GetMapping("/dashboard") public BackofficeQueryService.Dashboard dashboard(){return query.dashboard();}
    @GetMapping("/users") public List<BackofficeQueryService.UserSummary> users(){return query.users();}
    @GetMapping("/users/{id}") public BackofficeQueryService.UserDetail user(@PathVariable Long id){return query.user(id);}
    @PostMapping("/users") public BackofficeQueryService.UserDetail createUser(@Valid @RequestBody UserRequest r){AppUser u=users.create(r.login(),r.firstName(),r.lastName(),r.company(),r.email(),r.password(),r.enabled(),safe(r.roleIds()));audit.record("USER_CREATED","admin",u.getLogin(),null);return query.user(u.getId());}
    @PutMapping("/users/{id}") public BackofficeQueryService.UserDetail updateUser(@PathVariable Long id,@Valid @RequestBody UserRequest r){AppUser u=users.update(id,r.login(),r.firstName(),r.lastName(),r.company(),r.email(),r.password(),r.enabled(),safe(r.roleIds()));audit.record("USER_UPDATED","admin",u.getLogin(),null);return query.user(u.getId());}
    @DeleteMapping("/users/{id}") public Map<String,Object> disableUser(@PathVariable Long id){AppUser u=users.disable(id);sessions.terminateForUserByAdmin(id);audit.record("USER_DISABLED","admin",u.getLogin(),null);return Map.of("ok",true);}
    @PostMapping("/users/{id}/devices/revoke") public Map<String,Object> revoke(@PathVariable Long id,@RequestBody DeviceRevokeRequest r){users.revokeDevice(id,r.deviceId());return Map.of("ok",true);}
    @GetMapping("/roles") public List<BackofficeQueryService.RoleView> roles(){return query.roles();}
    @PostMapping("/roles") public BackofficeQueryService.RoleView createRole(@Valid @RequestBody RoleRequest r){PromptProfile p=roles.create(r.name(),r.description(),r.livePrompt(),r.evaluationPrompt(),r.liveModel(),r.evaluationModel(),r.enabled(),r.sortOrder());audit.record("ROLE_CREATED","admin",p.getName(),null);return query.roles().stream().filter(x->x.id().equals(p.getId())).findFirst().orElseThrow();}
    @PutMapping("/roles/{id}") public BackofficeQueryService.RoleView updateRole(@PathVariable Long id,@Valid @RequestBody RoleRequest r){roles.update(id,r.name(),r.description(),r.livePrompt(),r.evaluationPrompt(),r.liveModel(),r.evaluationModel(),r.enabled(),r.sortOrder());audit.record("ROLE_UPDATED","admin",String.valueOf(id),null);return query.roles().stream().filter(x->x.id().equals(id)).findFirst().orElseThrow();}
    @DeleteMapping("/roles/{id}") public Map<String,Object> disableRole(@PathVariable Long id){roles.disable(id);return Map.of("ok",true);}
    @GetMapping("/credentials") public List<BackofficeQueryService.CredentialView> credentials(){return query.credentials();}
    @PostMapping("/credentials") public BackofficeQueryService.CredentialView createCredential(@Valid @RequestBody CredentialRequest r){AiCredential c=credentials.create(r.name(),r.apiKey(),r.enabled(),r.maxConcurrentSessions());return credential(c.getId());}
    @PutMapping("/credentials/{id}") public BackofficeQueryService.CredentialView updateCredential(@PathVariable Long id,@Valid @RequestBody CredentialRequest r){credentials.update(id,r.name(),r.apiKey(),r.enabled(),r.maxConcurrentSessions());return credential(id);}
    @DeleteMapping("/credentials/{id}") public Map<String,Object> disableCredential(@PathVariable Long id){credentials.disable(id);return Map.of("ok",true);}
    @PostMapping("/credentials/{id}/test") public AiCredentialService.TestResult testCredential(@PathVariable Long id){return credentials.test(id);}
    @GetMapping("/sessions") public List<BackofficeQueryService.SessionView> sessions(){return query.sessions();}
    @PostMapping("/sessions/{id}/terminate") public Map<String,Object> terminate(@PathVariable UUID id){sessions.terminateByAdmin(id);return Map.of("ok",true);}
    @GetMapping("/system") public SystemView system(){return SystemView.from(system.get());}
    @PutMapping("/system") public SystemView system(@Valid @RequestBody SystemRequest r){return SystemView.from(system.update("",r.minimumClientVersion(),r.latestClientVersion(),r.clientDownloadUrl(),r.defaultModel(),false,false));}
    @GetMapping("/audit") public List<BackofficeQueryService.AuditView> audit(){return query.audit();}
    private BackofficeQueryService.CredentialView credential(Long id){return query.credentials().stream().filter(c->c.id().equals(id)).findFirst().orElseThrow(()->ApiException.notFound("AI-ключ не найден."));}private Set<Long> safe(Set<Long> ids){return ids==null?Set.of():ids;}
    public record DeviceRevokeRequest(@NotBlank String deviceId){} public record UserRequest(@NotBlank String login,@NotBlank String firstName,@NotBlank String lastName,@NotBlank String company,String email,String password,boolean enabled,Set<Long> roleIds){}
    public record RoleRequest(@NotBlank String name,String description,String livePrompt,String evaluationPrompt,String liveModel,String evaluationModel,boolean enabled,int sortOrder){} public record CredentialRequest(@NotBlank String name,String apiKey,boolean enabled,int maxConcurrentSessions){}
    public record SystemRequest(@NotBlank String minimumClientVersion,@NotBlank String latestClientVersion,String clientDownloadUrl,@NotBlank String defaultModel){} public record SystemView(String minimumClientVersion,String latestClientVersion,String clientDownloadUrl,String defaultModel,java.time.Instant updatedAt){static SystemView from(SystemConfig c){return new SystemView(c.getMinimumClientVersion(),c.getLatestClientVersion(),c.getClientDownloadUrl(),c.getDefaultModel(),c.getUpdatedAt());}}
}
