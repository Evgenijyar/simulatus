-- Align the singleton system_config primary key with the JPA Long mapping.
-- V1 is already applied in production, so its checksum must remain unchanged.
ALTER TABLE system_config
    ALTER COLUMN id TYPE BIGINT
    USING id::BIGINT;
