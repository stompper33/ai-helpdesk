package com.helpdesk.ai;

import com.helpdesk.domain.Enums.CustomerTier;
import com.helpdesk.domain.Enums.OrderStatus;
import com.helpdesk.domain.Enums.TicketCategory;
import com.helpdesk.domain.TicketPriority;
import com.helpdesk.web.dto.Responses.TicketDetailResponse;

import java.math.BigDecimal;

/**
 * The JSON contract with ai-service. These mirror the pydantic models in
 * ai-service/app/schemas.py, so keep the two in sync when either changes.
 */
public final class AiDtos {

    private AiDtos() {
    }

    // ---- sent to AI service (python) -------------------------------------------------------

    public record AiTriageRequest(Long ticketId, String subject, String description, CustomerTier customerTier,
                                  TicketPriority currentPriority, OrderContext order) {
    }

    public record OrderContext(String orderNumber, String productName, OrderStatus status, BigDecimal amount) {
    }

    // ---- returned by AI service (python) ---------------------------------------------------

    public record AiTriageResult(TicketCategory category, TicketPriority priority, String sentiment,
                                 String summary, String reasoning, double confidence,
                                 String provider, String model, long latencyMs, Usage usage) {
    }

    public record Usage(int inputTokens, int outputTokens) {
    }

    // ---- returned by our endpoint ---------------------------------------------

    public enum Outcome {
        /** Category and priority were written to the ticket. */
        APPLIED,
        /** Confidence was below ai-service.min-confidence; a human should decide. */
        LOW_CONFIDENCE,
        /** apply=false was requested; nothing was changed. */
        PREVIEW
    }

    public record AiTriageResponse(Long ticketId, Outcome outcome, AiTriageResult suggestion,
                                   TicketDetailResponse ticket) {
    }
}
