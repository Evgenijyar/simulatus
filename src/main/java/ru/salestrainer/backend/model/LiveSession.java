package ru.salestrainer.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="live_session")
public class LiveSession {
    @Id private UUID id;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="user_id",nullable=false) private AppUser user;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="prompt_profile_id",nullable=false) private PromptProfile promptProfile;
    @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="ai_credential_id",nullable=false) private AiCredential aiCredential;
    @Column(nullable=false,length=30) private String status;
    @Column(name="device_id",nullable=false,length=180) private String deviceId;
    @Column(name="client_version",length=60) private String clientVersion;
    @Column(name="prompt_version",nullable=false) private int promptVersion=1;
    @Column(name="started_at",nullable=false) private Instant startedAt;
    @Column(name="activated_at") private Instant activatedAt;
    @Column(name="closed_at") private Instant closedAt;
    @Column(name="lease_expires_at",nullable=false) private Instant leaseExpiresAt;
    @Column(name="token_expires_at") private Instant tokenExpiresAt;
    @Column(name="close_reason",length=500) private String closeReason;
    @Column(name="completion_source",length=40) private String completionSource;
    @Column(nullable=false,columnDefinition="text") private String transcript="";
    private Integer score;
    @Column(name="evaluation_summary",columnDefinition="text") private String evaluationSummary;
    @Column(name="evaluation_json",columnDefinition="text") private String evaluationJson;
    @Column(name="evaluated_at") private Instant evaluatedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;} public AppUser getUser(){return user;} public void setUser(AppUser v){user=v;}
    public PromptProfile getPromptProfile(){return promptProfile;} public void setPromptProfile(PromptProfile v){promptProfile=v;}
    public AiCredential getAiCredential(){return aiCredential;} public void setAiCredential(AiCredential v){aiCredential=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;} public String getDeviceId(){return deviceId;} public void setDeviceId(String v){deviceId=v;}
    public String getClientVersion(){return clientVersion;} public void setClientVersion(String v){clientVersion=v;} public int getPromptVersion(){return promptVersion;} public void setPromptVersion(int v){promptVersion=v;}
    public Instant getStartedAt(){return startedAt;} public void setStartedAt(Instant v){startedAt=v;} public Instant getActivatedAt(){return activatedAt;} public void setActivatedAt(Instant v){activatedAt=v;}
    public Instant getClosedAt(){return closedAt;} public void setClosedAt(Instant v){closedAt=v;} public Instant getLeaseExpiresAt(){return leaseExpiresAt;} public void setLeaseExpiresAt(Instant v){leaseExpiresAt=v;}
    public Instant getTokenExpiresAt(){return tokenExpiresAt;} public void setTokenExpiresAt(Instant v){tokenExpiresAt=v;} public String getCloseReason(){return closeReason;} public void setCloseReason(String v){closeReason=v;}
    public String getCompletionSource(){return completionSource;} public void setCompletionSource(String v){completionSource=v;} public String getTranscript(){return transcript;} public void setTranscript(String v){transcript=v==null?"":v;}
    public Integer getScore(){return score;} public void setScore(Integer v){score=v;} public String getEvaluationSummary(){return evaluationSummary;} public void setEvaluationSummary(String v){evaluationSummary=v;}
    public String getEvaluationJson(){return evaluationJson;} public void setEvaluationJson(String v){evaluationJson=v;} public Instant getEvaluatedAt(){return evaluatedAt;} public void setEvaluatedAt(Instant v){evaluatedAt=v;}
}
