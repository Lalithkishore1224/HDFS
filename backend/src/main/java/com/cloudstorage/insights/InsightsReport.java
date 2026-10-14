package com.cloudstorage.insights;

import java.time.Instant;
import java.util.List;

/**
 * What the admin sees.
 *
 * @param status      overall health: OK, ATTENTION or CRITICAL (null when an AI answer did not include one)
 * @param headline    one sentence that says the most important thing
 * @param summary     a short explanation of the situation
 * @param findings    what the numbers show, one fact per line
 * @param suggestions what to do about it
 * @param source      whether an AI wrote it or the built-in summarizer did
 * @param model       the AI model, or null for the built-in summary
 * @param note        explains why the built-in summary was used; null when the AI answered
 */
public record InsightsReport(
        String status,
        String headline,
        String summary,
        List<String> findings,
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
