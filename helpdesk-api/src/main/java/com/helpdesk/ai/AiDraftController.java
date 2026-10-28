package com.helpdesk.ai;

import com.helpdesk.ai.AgentDtos.AiDraftResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tickets")
@Tag(name = "AI reply drafting")
public class AiDraftController {

    private final AiDraftService aiDraftService;

    public AiDraftController(AiDraftService aiDraftService) {
        this.aiDraftService = aiDraftService;
    }

    @PostMapping("/{id}/ai-draft")
    @Operation(summary = "Ask the AI agent to draft a reply",
            description = "The Python agent looks up orders, ticket history and policies through this API, then "
                    + "drafts a reply. It is stored as an internal AI comment for a human to review and send.")
    public AiDraftResponse draft(@PathVariable Long id) {
        return aiDraftService.draft(id);
    }
}
