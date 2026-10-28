package com.helpdesk.ai;

import com.helpdesk.ai.AiDtos.AiTriageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tickets")
@Tag(name = "AI triage")
public class AiTriageController {

    private final AiTriageService aiTriageService;

    public AiTriageController(AiTriageService aiTriageService) {
        this.aiTriageService = aiTriageService;
    }

    @PostMapping("/{id}/ai-triage")
    @Operation(summary = "Ask the Python ai-service to triage this ticket",
            description = "apply=true (default) writes category/priority when confidence >= ai-service.min-confidence "
                    + "and records an internal AI comment. apply=false only previews the suggestion.")
    public AiTriageResponse aiTriage(@PathVariable Long id, @RequestParam(defaultValue = "true") boolean apply) {
        return aiTriageService.triage(id, apply);
    }
}
