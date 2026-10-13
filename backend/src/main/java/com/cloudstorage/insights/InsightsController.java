package com.cloudstorage.insights;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Admin only: the {@code /api/admin/**} rule in the security configuration enforces that. */
@RestController
@RequestMapping("/api/admin/insights")
class InsightsController {

    private final InsightsService insights;

    InsightsController(InsightsService insights) {
        this.insights = insights;
    }

    @PostMapping
    InsightsReport summarise() {
        return insights.summarise();
    }
}
