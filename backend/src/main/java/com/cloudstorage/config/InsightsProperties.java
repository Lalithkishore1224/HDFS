package com.cloudstorage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the admin storage summary, which is written by Google's Gemini API.
 *
 * @param apiKey         Gemini API key; blank means "no AI", and a built-in summary is produced instead
 * @param model          Gemini model id, by default {@code gemini-3.8-flash}
 * @param maxTokens      upper bound on the answer including the model's internal reasoning
 * @param timeoutSeconds how long to wait for the AI before falling back to the built-in summary
 * @param baseUrl        API base URL; only changed by tests to point at a local fake
 */
@ConfigurationProperties("insights")
public record InsightsProperties(String apiKey, String model, int maxTokens, int timeoutSeconds, String baseUrl) {

    public static final String DEFAULT_MODEL = "gemini-3.8-flash";
    public static final String DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com";

    public InsightsProperties {
        apiKey = apiKey == null ? "" : apiKey.strip();
        model = model == null || model.isBlank() ? DEFAULT_MODEL : model.strip();
        maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
        timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.strip();
    }

    public boolean hasApiKey() {
        return !apiKey.isEmpty();
    }
}
