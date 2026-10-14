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
            You are a storage-operations analyst advising the administrator of a self-hosted cloud file storage
            system (a Google-Drive-like service). Analyse the numbers like an engineer; do not just repeat them.

            How the system works, so you can reason about the numbers:
            - Every file is cut into chunks; each chunk is kept on several storage nodes (the replication factor),
              so a node can fail without any file becoming unreadable.
            - A background optimizer archives ("cold") files that are unlikely to be opened again by compressing
              them. It uses a prediction model and a threshold: a higher threshold archives more files.
            - Opening an archived file first restores it, which makes that download slower. Restores happen only
              for files that were archived and then needed again.
            - Users never see tiers or nodes; only the administrator does.

            You receive only aggregate numbers: no file names, folder names, contents or personal data.

            Your task:
            1. Judge overall health across four areas: redundancy (nodes online against the replication factor,
               chunks with too few copies), performance (time to start a download, share of downloads that waited
               on a restore), efficiency (space saved, share of files archived, optimizer activity) and accounts
               (anyone waiting for approval).
            2. Say what matters most right now and why, using the numbers as evidence. Explain the likely cause,
               not only the symptom. If the data is too small or too new to conclude, say that plainly.
            3. Give practical suggestions. Each one must name a concrete action, cite the number that justifies it
               and say what improvement to expect. Order them by importance. Do not suggest what is already done.

            Rules: use only the numbers provided; never invent numbers, file names, users or events; do not
            repeat the same fact in several places; keep sentences short and in plain language for a
            non-specialist administrator; do not mention that you are an AI.

            Reply with JSON only, in exactly this shape and nothing else:
            {"status": "OK" | "ATTENTION" | "CRITICAL",
             "headline": "<one sentence with the single most important point>",
             "summary": "<3 to 5 sentences: what is going on and why>",
             "findings": ["<one fact with its number>", "... 3 to 5 items covering redundancy, performance, efficiency, accounts"],
             "suggestions": ["<action> - <why, citing a number> - <expected effect>", "... 3 to 5 items, most important first"]}
            Use "OK" when nothing needs action, "ATTENTION" when something should be done soon, and "CRITICAL"
            when files are at risk or users are being hurt right now.
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
        StringBuilder text = new StringBuilder(String.format(Locale.ROOT, """
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
                        : "has not run since the server started"));

        text.append(String.format(Locale.ROOT, """

                Derived figures:
                - average file size: %d bytes
                - share of files archived: %.1f%%
                """, s.averageFileBytes(), s.coldFilePercent()));

        if (s.clusterKnown()) {
            text.append(String.format(Locale.ROOT, """

                    Storage nodes:
                    - nodes: %d (serving: %d)
                    - copies kept of every chunk (replication factor): %d
                    - chunks stored: %d
                    - chunks with fewer copies than wanted: %d
                    - more nodes that could fail with every file still readable: %d
                    """, s.storageNodes(), s.nodesOnline(), s.replicationFactor(), s.chunks(),
                    s.underReplicatedChunks(), s.nodesThatCanFail()));
        }
        if (s.accountsKnown()) {
            text.append(String.format(Locale.ROOT, """

                    Accounts:
                    - accounts: %d (waiting for approval: %d)
                    - storage allowance per account: %d bytes
                    """, s.accounts(), s.pendingAccounts(), s.quotaBytesPerAccount()));
        }
        return text.toString();
    }

    private InsightsReport parse(String answer, Instant now) {
        String cleaned = stripFences(answer);
        try {
            JsonNode root = json.readTree(cleaned);
            String summary = root.path("summary").asText("").strip();
            if (!summary.isEmpty()) {
                return new InsightsReport(
                        status(root.path("status").asText("")),
                        blankToNull(root.path("headline").asText("")),
                        summary,
                        texts(root.path("findings")),
                        texts(root.path("suggestions")),
                        InsightsReport.Source.AI, ai.modelName(), null, now);
            }
        } catch (Exception ignored) {
            // not JSON: fall through and show the text as it is
        }
        return new InsightsReport(null, null, answer.strip(), List.of(), List.of(),
                InsightsReport.Source.AI, ai.modelName(), null, now);
    }

    private static List<String> texts(JsonNode array) {
        List<String> items = new ArrayList<>();
        array.forEach(node -> {
            String text = node.asText("").strip();
            if (!text.isEmpty()) {
                items.add(text);
            }
        });
        return List.copyOf(items);
    }

    private static String status(String value) {
        String upper = value.strip().toUpperCase(Locale.ROOT);
        return upper.equals("OK") || upper.equals("ATTENTION") || upper.equals("CRITICAL") ? upper : null;
    }

    private static String blankToNull(String value) {
        return value.isBlank() ? null : value.strip();
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
                BuiltInSummarizer.status(snapshot),
                BuiltInSummarizer.headline(snapshot),
                BuiltInSummarizer.summary(snapshot),
                BuiltInSummarizer.findings(snapshot),
                BuiltInSummarizer.suggestions(snapshot),
                InsightsReport.Source.BUILT_IN,
                null,
                note,
                now);
    }
}
