package com.helpdesk.ai;

import com.helpdesk.ai.AgentDtos.AgentDraftRequest;
import com.helpdesk.ai.AgentDtos.AgentDraftResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls the Python reply-drafting agent. Builds its own RestClient (longer read timeout
 * than triage) instead of exposing a second RestClient bean, so stage 2 wiring is untouched.
 */
@Component
@EnableConfigurationProperties(AiAgentProperties.class)
public class AiAgentClient {

    private static final Logger log = LoggerFactory.getLogger(AiAgentClient.class);

    private final RestClient restClient;

    public AiAgentClient(RestClient.Builder builder, AiServiceProperties props, AiAgentProperties agentProps) {
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(props.connectTimeout());
        requestFactory.setReadTimeout(agentProps.readTimeout());
        builder = builder.baseUrl(props.baseUrl()).requestFactory(requestFactory);
        if (props.apiKey() != null && !props.apiKey().isBlank()) {
            builder = builder.defaultHeader("X-Api-Key", props.apiKey());
        }
        this.restClient = builder.build();
    }

    public AgentDraftResult draftReply(Long ticketId) {
        try {
            AgentDraftResult result = restClient.post()
                    .uri("/v1/agent/draft-reply")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new AgentDraftRequest(ticketId))
                    .retrieve()
                    .body(AgentDraftResult.class);
            if (result == null || result.customerReply() == null || result.customerReply().isBlank()) {
                throw new AiServiceException("AI_BAD_RESPONSE", HttpStatus.BAD_GATEWAY,
                        "ai-service returned an empty draft", null);
            }
            log.info("AI draft ticket={} steps={} tools={} confidence={} ({} via {}, {} ms)", ticketId,
                    result.steps(), result.toolCalls() == null ? 0 : result.toolCalls().size(),
                    result.confidence(), result.model(), result.provider(), result.latencyMs());
            return result;
        } catch (ResourceAccessException e) {
            throw new AiServiceException("AI_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "ai-service is not reachable or timed out: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            throw new AiServiceException("AI_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
                    "ai-service returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new AiServiceException("AI_BAD_RESPONSE", HttpStatus.BAD_GATEWAY,
                    "Could not read ai-service response: " + e.getMessage(), e);
        }
    }
}
