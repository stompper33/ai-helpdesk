package com.helpdesk.ai;

import com.helpdesk.ai.AiDtos.AiTriageRequest;
import com.helpdesk.ai.AiDtos.AiTriageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Thin HTTP client for ai-service. Translates every failure into an {@link AiServiceException}. */
@Component
public class AiTriageClient {

    private static final Logger log = LoggerFactory.getLogger(AiTriageClient.class);

    private final RestClient aiRestClient;

    public AiTriageClient(RestClient aiRestClient) {
        this.aiRestClient = aiRestClient;
    }

    public AiTriageResult triage(AiTriageRequest request) {
        try {
            AiTriageResult result = aiRestClient.post()
                    .uri("/v1/triage")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(AiTriageResult.class);
            if (result == null || result.category() == null || result.priority() == null) {
                throw new AiServiceException("AI_BAD_RESPONSE", HttpStatus.BAD_GATEWAY,
                        "ai-service returned an empty or incomplete triage result", null);
            }
            log.info("AI triage ticket={} -> {} / {} (confidence {}, {} via {}, {} ms)", request.ticketId(),
                    result.category(), result.priority(), result.confidence(), result.model(), result.provider(),
                    result.latencyMs());
            return result;
        } catch (ResourceAccessException e) {
            // connection refused, DNS failure, or timeout
            throw new AiServiceException("AI_SERVICE_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE,
                    "ai-service is not reachable: " + e.getMessage(), e);
        } catch (RestClientResponseException e) {
            // ai-service answered with 4xx/5xx (e.g. 401 bad API key, 502 OpenAI failure)
            throw new AiServiceException("AI_SERVICE_ERROR", HttpStatus.BAD_GATEWAY,
                    "ai-service returned " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            // e.g. JSON we can't map, such as an unknown category value
            throw new AiServiceException("AI_BAD_RESPONSE", HttpStatus.BAD_GATEWAY,
                    "Could not read ai-service response: " + e.getMessage(), e);
        }
    }
}
