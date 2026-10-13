package com.cloudstorage.insights;

import java.time.Instant;
import java.util.List;

/**
 * What the admin sees.
 *
 * @param source whether an AI wrote it or the built-in summarizer did
 * @param model  the AI model, or null for the built-in summary
 * @param note   explains why the built-in summary was used; null when the AI answered
 */
public record InsightsReport(
        String summary,
        List<String> suggestions,
        Source source,
        String model,
        String note,
        Instant generatedAt) {

    public enum Source {
        AI,
        BUILT_IN
    }
}
