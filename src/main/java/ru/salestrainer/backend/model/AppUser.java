package ru.salestrainer.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 100) private String login;
    @Column(name = "first_name", nullable = false, length = 100) private String firstName;
    @Column(name = "last_name", nullable = false, length = 100) private String lastName;
    @Column(nullable = false, length = 180) private String company;
    @Column(length = 220) private String email;
    @Column(name = "password_hash", nullable = false, length = 512) private String passwordHash;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "last_login_at") private Instant lastLoginAt;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_prompt_profile", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "prompt_profile_id"))
    @OrderBy("sortOrder ASC, name ASC")
    private Set<PromptProfile> promptProfiles = new LinkedHashSet<>();

    @PrePersist void prePersist(){ Instant now=Instant.now(); createdAt=now; updatedAt=now; }
    @PreUpdate void preUpdate(){ updatedAt=Instant.now(); }

    public Long getId(){return id;} public String getLogin(){return login;} public void setLogin(String v){login=v;}
    public String getFirstName(){return firstName;} public void setFirstName(String v){firstName=v;}
    public String getLastName(){return lastName;} public void setLastName(String v){lastName=v;}
    public String getCompany(){return company;} public void setCompany(String v){company=v;}
    public String getDisplayName(){return ((firstName==null?"":firstName)+" "+(lastName==null?"":lastName)).trim();}
    public String getEmail(){return email;} public void setEmail(String v){email=v;}
    public String getPasswordHash(){return passwordHash;} public void setPasswordHash(String v){passwordHash=v;}
    public boolean isEnabled(){return enabled;} public void setEnabled(boolean v){enabled=v;}
    public Instant getCreatedAt(){return createdAt;} public Instant getUpdatedAt(){return updatedAt;}
    public Instant getLastLoginAt(){return lastLoginAt;} public void setLastLoginAt(Instant v){lastLoginAt=v;}
    public Set<PromptProfile> getPromptProfiles(){return promptProfiles;}
    public void setPromptProfiles(Set<PromptProfile> v){promptProfiles=v==null?new LinkedHashSet<>():v;}
}
