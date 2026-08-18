package ru.salestrainer.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.salestrainer.backend.controller.ApiException;
import ru.salestrainer.backend.model.AppUser;
import ru.salestrainer.backend.model.PromptProfile;
import ru.salestrainer.backend.repository.*;
import ru.salestrainer.backend.security.PasswordHasher;
import java.util.*;

@Service
public class UserService {
    private final AppUserRepository users; private final PromptProfileRepository prompts;
    private final ClientAccessTokenRepository accessTokens; private final ClientRefreshTokenRepository refreshTokens;
    private final PasswordHasher passwordHasher;
    public UserService(AppUserRepository users, PromptProfileRepository prompts, ClientAccessTokenRepository accessTokens,
                       ClientRefreshTokenRepository refreshTokens, PasswordHasher passwordHasher){this.users=users;this.prompts=prompts;this.accessTokens=accessTokens;this.refreshTokens=refreshTokens;this.passwordHasher=passwordHasher;}
    @Transactional(readOnly=true) public List<AppUser> list(){return users.findAllByOrderByLastNameAscFirstNameAsc();}
    @Transactional(readOnly=true) public AppUser require(Long id){return users.findById(id).orElseThrow(()->ApiException.notFound("Пользователь не найден."));}
    @Transactional public AppUser create(String login,String firstName,String lastName,String company,String email,String password,boolean enabled,Set<Long> roleIds){
        String normalized=normalizeLogin(login); if(users.existsByLoginIgnoreCase(normalized)) throw ApiException.conflict("LOGIN_EXISTS","Пользователь с таким логином уже существует.");
        if(password==null||password.length()<6) throw ApiException.badRequest("WEAK_PASSWORD","Пароль должен содержать минимум 6 символов.");
        AppUser u=new AppUser();u.setLogin(normalized);u.setFirstName(required(firstName,"Укажите имя."));u.setLastName(required(lastName,"Укажите фамилию."));u.setCompany(required(company,"Укажите компанию."));u.setEmail(blankToNull(email));u.setPasswordHash(passwordHasher.hash(password));u.setEnabled(enabled);u.setPromptProfiles(loadRoles(roleIds));return users.save(u);
    }
    @Transactional public AppUser update(Long id,String login,String firstName,String lastName,String company,String email,String newPassword,boolean enabled,Set<Long> roleIds){
        AppUser u=require(id);String normalized=normalizeLogin(login);users.findByLoginIgnoreCase(normalized).filter(x->!x.getId().equals(id)).ifPresent(x->{throw ApiException.conflict("LOGIN_EXISTS","Пользователь с таким логином уже существует.");});
        boolean revoke=u.isEnabled()&&!enabled;u.setLogin(normalized);u.setFirstName(required(firstName,"Укажите имя."));u.setLastName(required(lastName,"Укажите фамилию."));u.setCompany(required(company,"Укажите компанию."));u.setEmail(blankToNull(email));u.setEnabled(enabled);u.setPromptProfiles(loadRoles(roleIds));
        if(newPassword!=null&&!newPassword.isBlank()){if(newPassword.length()<6)throw ApiException.badRequest("WEAK_PASSWORD","Пароль должен содержать минимум 6 символов.");u.setPasswordHash(passwordHasher.hash(newPassword));revoke=true;}
        AppUser saved=users.save(u);if(revoke)revokeTokens(id);return saved;
    }
    @Transactional public AppUser disable(Long id){AppUser u=require(id);u.setEnabled(false);revokeTokens(id);return users.save(u);}
    @Transactional public void revokeTokens(Long id){accessTokens.deleteByUser_Id(id);refreshTokens.deleteByUser_Id(id);}
    @Transactional public void revokeDevice(Long id,String deviceId){require(id);if(deviceId==null||deviceId.isBlank())throw ApiException.badRequest("DEVICE_ID_REQUIRED","deviceId обязателен.");accessTokens.deleteByUser_IdAndDeviceId(id,deviceId.trim());refreshTokens.deleteByUser_IdAndDeviceId(id,deviceId.trim());}
    private Set<PromptProfile> loadRoles(Set<Long> ids){if(ids==null||ids.isEmpty())return new LinkedHashSet<>();List<PromptProfile> found=prompts.findAllByIdIn(ids);if(found.size()!=ids.size())throw ApiException.badRequest("ROLE_NOT_FOUND","Одна из выбранных ролей не существует.");return new LinkedHashSet<>(found);}
    private String normalizeLogin(String v){return required(v,"Логин обязателен.").toLowerCase(Locale.ROOT);} private String required(String v,String m){if(v==null||v.isBlank())throw ApiException.badRequest("VALIDATION_ERROR",m);return v.trim();} private String blankToNull(String v){return v==null||v.isBlank()?null:v.trim();}
}
