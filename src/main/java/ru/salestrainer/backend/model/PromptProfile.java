package ru.salestrainer.backend.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name="prompt_profile")
public class PromptProfile {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false,unique=true,length=120) private String name;
    @Column(length=500) private String description;
    @Column(name="system_prompt",nullable=false,columnDefinition="text") private String systemPrompt="";
    @Column(name="evaluation_prompt",nullable=false,columnDefinition="text") private String evaluationPrompt="";
    @Column(nullable=false,length=160) private String model="gemini-3.1-flash-live-preview";
    @Column(name="evaluation_model",nullable=false,length=160) private String evaluationModel="gemini-3.1-flash-lite";
    @Column(nullable=false) private boolean enabled=true;
    @Column(nullable=false) private int version=1;
    @Column(name="sort_order",nullable=false) private int sortOrder=100;
    @Column(name="created_at",nullable=false) private Instant createdAt;
    @Column(name="updated_at",nullable=false) private Instant updatedAt;
    @PrePersist void prePersist(){Instant now=Instant.now();createdAt=now;updatedAt=now;}
    @PreUpdate void preUpdate(){updatedAt=Instant.now();}
    public Long getId(){return id;} public String getName(){return name;} public void setName(String v){name=v;}
    public String getDescription(){return description;} public void setDescription(String v){description=v;}
    public String getSystemPrompt(){return systemPrompt;} public void setSystemPrompt(String v){systemPrompt=v==null?"":v;}
    public String getEvaluationPrompt(){return evaluationPrompt;} public void setEvaluationPrompt(String v){evaluationPrompt=v==null?"":v;}
    public String getModel(){return model;} public void setModel(String v){model=v;}
    public String getEvaluationModel(){return evaluationModel;} public void setEvaluationModel(String v){evaluationModel=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public int getVersion(){return version;} public void setVersion(int v){version=v;} public void bumpVersion(){version++;}
    public int getSortOrder(){return sortOrder;} public void setSortOrder(int v){sortOrder=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
}
