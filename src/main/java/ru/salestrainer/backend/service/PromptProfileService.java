package ru.salestrainer.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.salestrainer.backend.controller.ApiException;
import ru.salestrainer.backend.model.PromptProfile;
import ru.salestrainer.backend.repository.PromptProfileRepository;
import java.util.List;

@Service
public class PromptProfileService {
    private final PromptProfileRepository repo; private final String defaultLive; private final String defaultEval;
    public PromptProfileService(PromptProfileRepository repo,@Value("${trainer.gemini.default-live-model:gemini-3.1-flash-live-preview}")String defaultLive,@Value("${trainer.gemini.default-evaluation-model:gemini-3.1-flash-lite}")String defaultEval){this.repo=repo;this.defaultLive=defaultLive;this.defaultEval=defaultEval;}
    @Transactional(readOnly=true) public List<PromptProfile> list(){return repo.findAllByOrderBySortOrderAscNameAsc();}
    @Transactional(readOnly=true) public PromptProfile require(Long id){return repo.findById(id).orElseThrow(()->ApiException.notFound("Роль не найдена."));}
    @Transactional public PromptProfile create(String name,String description,String livePrompt,String evaluationPrompt,String liveModel,String evaluationModel,boolean enabled,int sortOrder){
        String n=req(name,"Укажите название роли.");if(repo.existsByNameIgnoreCase(n))throw ApiException.conflict("ROLE_EXISTS","Роль с таким названием уже существует.");PromptProfile p=new PromptProfile();apply(p,n,description,livePrompt,evaluationPrompt,liveModel,evaluationModel,enabled,sortOrder);return repo.save(p);}
    @Transactional public PromptProfile update(Long id,String name,String description,String livePrompt,String evaluationPrompt,String liveModel,String evaluationModel,boolean enabled,int sortOrder){PromptProfile p=require(id);String n=req(name,"Укажите название роли.");repo.findAllByOrderBySortOrderAscNameAsc().stream().filter(x->!x.getId().equals(id)&&x.getName().equalsIgnoreCase(n)).findAny().ifPresent(x->{throw ApiException.conflict("ROLE_EXISTS","Роль с таким названием уже существует.");});apply(p,n,description,livePrompt,evaluationPrompt,liveModel,evaluationModel,enabled,sortOrder);p.bumpVersion();return repo.save(p);}
    @Transactional public PromptProfile disable(Long id){PromptProfile p=require(id);p.setEnabled(false);p.bumpVersion();return repo.save(p);}
    private void apply(PromptProfile p,String n,String d,String lp,String ep,String lm,String em,boolean en,int order){p.setName(n);p.setDescription(blank(d));p.setSystemPrompt(blank(lp));p.setEvaluationPrompt(blank(ep));p.setModel(blank(lm).isBlank()?defaultLive:lm.trim());p.setEvaluationModel(blank(em).isBlank()?defaultEval:em.trim());p.setEnabled(en);p.setSortOrder(order);}
    private String req(String v,String m){if(v==null||v.isBlank())throw ApiException.badRequest("VALIDATION_ERROR",m);return v.trim();}private String blank(String v){return v==null?"":v.trim();}
}
