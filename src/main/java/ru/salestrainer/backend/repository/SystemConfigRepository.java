package ru.salestrainer.backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.salestrainer.backend.model.SystemConfig;

public interface SystemConfigRepository extends JpaRepository<SystemConfig, Long> {
}
