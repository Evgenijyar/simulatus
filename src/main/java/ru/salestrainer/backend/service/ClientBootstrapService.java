package ru.salestrainer.backend.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.salestrainer.backend.controller.ApiException;
import ru.salestrainer.backend.model.*;
import ru.salestrainer.backend.repository.AppUserRepository;
import java.util.*;

@Service
public class ClientBootstrapService {
    private final AppUserRepository users; private final SystemConfigService configService;
    public ClientBootstrapService(AppUserRepository users,SystemConfigService configService){this.users=users;this.configService=configService;}
    @Transactional(readOnly=true) public Bootstrap bootstrap(Long userId,String clientVersion){AppUser u=users.findById(userId).orElseThrow(()->ApiException.notFound("Пользователь не найден."));if(!u.isEnabled())throw ApiException.forbidden("Доступ пользователя отключён.");SystemConfig c=configService.get();List<Role> roles=u.getPromptProfiles().stream().filter(PromptProfile::isEnabled).sorted(Comparator.comparingInt(PromptProfile::getSortOrder).thenComparing(PromptProfile::getName,String.CASE_INSENSITIVE_ORDER)).map(p->new Role(p.getId(),p.getName(),p.getDescription())).toList();String version=clientVersion==null||clientVersion.isBlank()?"0.0.0":clientVersion.trim();return new Bootstrap(new UserInfo(u.getId(),u.getLogin(),u.getFirstName(),u.getLastName(),u.getCompany(),u.getDisplayName()),roles,new VersionInfo(c.getMinimumClientVersion(),c.getLatestClientVersion(),c.getClientDownloadUrl(),compare(version,c.getMinimumClientVersion())<0,compare(version,c.getLatestClientVersion())<0));}
    private int compare(String a,String b){int[] x=num(a),y=num(b);for(int i=0;i<Math.max(x.length,y.length);i++){int xv=i<x.length?x[i]:0,yv=i<y.length?y[i]:0;if(xv!=yv)return Integer.compare(xv,yv);}return 0;}private int[] num(String v){String[] p=(v==null?"":v.split("[-+]",2)[0]).split("\\.");int[] r=new int[p.length];for(int i=0;i<p.length;i++){try{String d=p[i].replaceAll("[^0-9]","");r[i]=d.isBlank()?0:Integer.parseInt(d);}catch(Exception ignored){}}return r;}
    public record Bootstrap(UserInfo user,List<Role> roles,VersionInfo version){} public record UserInfo(Long id,String login,String firstName,String lastName,String company,String displayName){} public record Role(Long id,String name,String description){} public record VersionInfo(String minimumSupported,String latest,String downloadUrl,boolean updateRequired,boolean updateAvailable){}
}
