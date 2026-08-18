CREATE TABLE prompt_profile (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    description VARCHAR(500),
    system_prompt TEXT NOT NULL DEFAULT '',
    evaluation_prompt TEXT NOT NULL DEFAULT '',
    model VARCHAR(160) NOT NULL DEFAULT 'gemini-3.1-flash-live-preview',
    evaluation_model VARCHAR(160) NOT NULL DEFAULT 'gemini-3.1-flash-lite',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    version INTEGER NOT NULL DEFAULT 1,
    sort_order INTEGER NOT NULL DEFAULT 100,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_user (
    id BIGSERIAL PRIMARY KEY,
    login VARCHAR(100) NOT NULL UNIQUE,
    first_name VARCHAR(100) NOT NULL,
    last_name VARCHAR(100) NOT NULL,
    company VARCHAR(180) NOT NULL,
    email VARCHAR(220),
    password_hash VARCHAR(512) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ
);
CREATE UNIQUE INDEX ux_app_user_login_lower ON app_user (lower(login));

CREATE TABLE user_prompt_profile (
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    prompt_profile_id BIGINT NOT NULL REFERENCES prompt_profile(id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, prompt_profile_id)
);
CREATE INDEX ix_user_prompt_profile_prompt ON user_prompt_profile(prompt_profile_id);

CREATE TABLE ai_credential (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    provider VARCHAR(40) NOT NULL DEFAULT 'GEMINI',
    encrypted_api_key TEXT NOT NULL,
    key_hint VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    max_concurrent_sessions INTEGER NOT NULL DEFAULT 1,
    health_status VARCHAR(40) NOT NULL DEFAULT 'UNKNOWN',
    last_error TEXT,
    last_checked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_ai_credential_capacity CHECK (max_concurrent_sessions BETWEEN 1 AND 100)
);

CREATE TABLE live_session (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE RESTRICT,
    prompt_profile_id BIGINT NOT NULL REFERENCES prompt_profile(id) ON DELETE RESTRICT,
    ai_credential_id BIGINT NOT NULL REFERENCES ai_credential(id) ON DELETE RESTRICT,
    status VARCHAR(30) NOT NULL,
    device_id VARCHAR(180) NOT NULL,
    client_version VARCHAR(60),
    prompt_version INTEGER NOT NULL DEFAULT 1,
    started_at TIMESTAMPTZ NOT NULL,
    activated_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    token_expires_at TIMESTAMPTZ,
    close_reason VARCHAR(500),
    completion_source VARCHAR(40),
    transcript TEXT NOT NULL DEFAULT '',
    score INTEGER,
    evaluation_summary TEXT,
    evaluation_json TEXT,
    evaluated_at TIMESTAMPTZ,
    CONSTRAINT ck_training_score CHECK (score IS NULL OR score BETWEEN 0 AND 100)
);
CREATE INDEX ix_live_session_user_started ON live_session(user_id, started_at DESC);
CREATE INDEX ix_live_session_credential_active ON live_session(ai_credential_id, status, lease_expires_at);
CREATE INDEX ix_live_session_lease ON live_session(status, lease_expires_at);

CREATE TABLE client_access_token (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_id VARCHAR(180) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_access_token_user ON client_access_token(user_id);
CREATE INDEX ix_access_token_expiry ON client_access_token(expires_at);

CREATE TABLE client_refresh_token (
    id UUID PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    device_id VARCHAR(180) NOT NULL,
    device_name VARCHAR(180),
    persistent BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    last_used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_refresh_token_user ON client_refresh_token(user_id);
CREATE INDEX ix_refresh_token_device ON client_refresh_token(device_id);
CREATE INDEX ix_refresh_token_expiry ON client_refresh_token(expires_at);

CREATE TABLE system_config (
    id SMALLINT PRIMARY KEY,
    global_prompt TEXT NOT NULL DEFAULT '',
    minimum_client_version VARCHAR(60) NOT NULL DEFAULT '0.1.0',
    latest_client_version VARCHAR(60) NOT NULL DEFAULT '0.1.0',
    client_download_url VARCHAR(500) NOT NULL DEFAULT '',
    default_model VARCHAR(160) NOT NULL DEFAULT 'gemini-3.1-flash-live-preview',
    feature_expanded_mode BOOLEAN NOT NULL DEFAULT FALSE,
    feature_manual_client_context BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_system_config_singleton CHECK (id = 1)
);
INSERT INTO system_config(id) VALUES (1);

CREATE TABLE audit_event (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    event_type VARCHAR(80) NOT NULL,
    actor VARCHAR(180),
    subject VARCHAR(300),
    detail TEXT
);
CREATE INDEX ix_audit_event_created ON audit_event(created_at DESC);
