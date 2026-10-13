package com.cloudstorage.insights;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Produces the admin's storage summary.
 * <p>
 * The numbers come from {@link StorageSnapshotProvider}; the AI only turns them into words. If the AI is
 * not configured or fails for any reason, the built-in summarizer answers instead, and the report says so.
 */
@Service
public class InsightsService {

    private static final Logger log = LoggerFactory.getLogger(InsightsService.class);

    /** The same numbers asked for again within this time return the previous answer instead of a new AI call. */
    private static final Duration CACHE_TIME = Duration.ofMinutes(5);

    static final String SYSTEM_PROMPT = """
            You are an assistant for the administrator of a cloud file storage system.
            You only receive aggregate storage statistics. Write a short plain-language summary and exactly
            three practical suggestions about storage usage and performance.
            Rules: use only the numbers provided; never invent numbers, file names or users; keep every
            sentence short; do not mention that you are an AI.
            Reply with JSON only, in exactly this shape and nothing else:
            {"summary": "<2 to 4 sentences>", "suggestions": ["<one sentence>", "<one sentence>", "<one sentence>"]}
            """;

    private final StorageSnapshotProvider snapshots;
    private final AiClient ai;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    private StorageSnapshot cachedFor;
    private InsightsReport cachedReport;

    public InsightsService(StorageSnapshotProvider snapshots, AiClient ai, Clock clock) {
        this.snapshots = snapshots;
        this.ai = ai;
        this.clock = clock;
    }

    public synchronized InsightsReport summarise() {
        StorageSnapshot snapshot = snapshots.current();
        Instant now = clock.instant();

        if (cachedReport != null && snapshot.equals(cachedFor)
                && Duration.between(cachedReport.generatedAt(), now).compareTo(CACHE_TIME) < 0) {
            return cachedReport;
        }

        InsightsReport report = ai.available() ? askAi(snapshot, now) : builtIn(snapshot, now,
                "No AI API key is configured on the server, so this summary was written without AI from the same numbers.");
        cachedFor = snapshot;
        cachedReport = report;
        return report;
    }

    private InsightsReport askAi(StorageSnapshot snapshot, Instant now) {
        try {
            String answer = ai.complete(SYSTEM_PROMPT, buildUserPrompt(snapshot));
            return parse(answer, now);
        } catch (RuntimeException e) {
            log.warn("AI summary unavailable, using the built-in summary: {}", e.toString());
            return builtIn(snapshot, now, "The AI service could not be reached, so this summary was written without AI.");
        }
    }

    /** Package-visible so tests can check exactly what would be sent. */
    static String buildUserPrompt(StorageSnapshot s) {
        return String.format(Locale.ROOT, """
                        Storage statistics:
                        - files: %d (hot: %d, cold/archived: %d)
                        - size as users see it: %d bytes
                        - size on disk after compression: %d bytes
                        - space saved: %.1f%%
                        - downloads: %d
                        - downloads that waited for an archived file to be restored: %d (%.1f%%)
                        - average time to start a download: %.0f ms
                        - optimizer threshold: %.2f (a higher value archives more files)
                        - optimizer last run: %s
                        """,
                s.fileCount(), s.hotFileCount(), s.coldFileCount(),
                s.logicalBytes(), s.storedBytes(), s.savedPercent(),
                s.downloads(), s.coldRestores(), s.coldRestorePercent(),
                s.averageResponseMs(), s.optimizerThreshold(),
                s.optimizerHasRun()
                        ? String.format(Locale.ROOT, "archived %d files, promoted %d files", s.lastRunMovedToCold(), s.lastRunMovedToHot())
                        : "has not run since the server started");
    }

    private InsightsReport parse(String answer, Instant now) {
        String cleaned = stripFences(answer);
        try {
            JsonNode root = json.readTree(cleaned);
            String summary = root.path("summary").asText("").strip();
            List<String> suggestions = new ArrayList<>();
            root.path("suggestions").forEach(node -> {
                String text = node.asText("").strip();
                if (!text.isEmpty()) {
                    suggestions.add(text);
                }
            });
            if (!summary.isEmpty()) {
                return new InsightsReport(summary, List.copyOf(suggestions), InsightsReport.Source.AI, ai.modelName(), null, now);
            }
        } catch (Exception ignored) {
            // not JSON: fall through and show the text as it is
        }
        return new InsightsReport(answer.strip(), List.of(), InsightsReport.Source.AI, ai.modelName(), null, now);
    }

    private static String stripFences(String text) {
        String trimmed = text.strip();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).strip();
            }
        }
        return trimmed;
    }

    private static InsightsReport builtIn(StorageSnapshot snapshot, Instant now, String note) {
        return new InsightsReport(
                BuiltInSummarizer.summary(snapshot),
                BuiltInSummarizer.suggestions(snapshot),
                InsightsReport.Source.BUILT_IN,
                null,
                note,
                now);
    }
}
