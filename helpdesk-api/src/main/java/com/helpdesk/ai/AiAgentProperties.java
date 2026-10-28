package com.helpdesk.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Stage 3 settings for the reply-drafting agent (prefix "ai-service.agent").
 * Base URL, API key and connect timeout are shared with {@link AiServiceProperties}.
 *
 * @param readTimeout the agent makes several LLM calls plus tool calls back into this
 *                    app, so it needs a longer timeout than single-shot triage
 */
@ConfigurationProperties(prefix = "ai-service.agent")
public record AiAgentProperties(@DefaultValue("90s") Duration readTimeout) {
}
