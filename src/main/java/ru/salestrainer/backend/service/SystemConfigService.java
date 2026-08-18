package ru.salestrainer.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.salestrainer.backend.model.SystemConfig;
import ru.salestrainer.backend.repository.SystemConfigRepository;

@Service
public class SystemConfigService {
    private static final long CONFIG_ID = 1L;
    private final SystemConfigRepository repository;
    private final String defaultModel;

    public SystemConfigService(SystemConfigRepository repository,
                               @Value("${trainer.gemini.default-live-model:gemini-3.1-flash-live-preview}") String defaultModel) {
        this.repository = repository;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public SystemConfig get() {
        return repository.findById(CONFIG_ID).orElseGet(() -> {
            SystemConfig config = new SystemConfig();
            config.setId(CONFIG_ID);
            config.setGlobalPrompt("");
            config.setDefaultModel(defaultModel);
            return repository.save(config);
        });
    }

    @Transactional
    public SystemConfig update(String globalPrompt, String minimumVersion, String latestVersion,
                               String clientDownloadUrl, String defaultModelValue, boolean expandedMode, boolean manualContext) {
        SystemConfig config = get();
        config.setGlobalPrompt(value(globalPrompt));
        config.setMinimumClientVersion(nonBlank(minimumVersion, "0.1.0"));
        config.setLatestClientVersion(nonBlank(latestVersion, config.getMinimumClientVersion()));
        config.setClientDownloadUrl(value(clientDownloadUrl));
        config.setDefaultModel(nonBlank(defaultModelValue, defaultModel));
        config.setFeatureExpandedMode(expandedMode);
        config.setFeatureManualClientContext(manualContext);
        return repository.save(config);
    }

    private static String value(String value) { return value == null ? "" : value.trim(); }
    private static String nonBlank(String value, String fallback) { return value == null || value.isBlank() ? fallback : value.trim(); }
}
