package com.helpdesk.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

import static com.helpdesk.ai.AiServiceConstants.*;

/**
 * Settings for calling the Python ai-service (prefix "ai-service" in application.yml).
 *
 * @param baseUrl        where ai-service runs
 * @param apiKey         optional shared secret, sent as X-Api-Key when not blank
 * @param connectTimeout how long to wait for a TCP connection (seconds)
 * @param readTimeout    how long to wait for the AI to answer (LLM calls could be slow) (seconds)
 * @param minConfidence  AI suggestions below this are recorded but not applied
 */
@ConfigurationProperties(prefix = "ai-service")
public record AiServiceProperties(
        @DefaultValue(DEFAULT_BASE_URL) String baseUrl,
        @DefaultValue(DEFAULT_API_KEY) String apiKey,
        @DefaultValue(DEFAULT_CONNECT_TIMEOUT) Duration connectTimeout,
        @DefaultValue(DEFAULT_READ_TIMEOUT) Duration readTimeout,
        @DefaultValue(DEFAULT_MIN_CONFIDENCE) double minConfidence) {
}
