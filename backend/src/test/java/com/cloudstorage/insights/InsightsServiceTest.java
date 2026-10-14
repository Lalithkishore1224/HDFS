package com.cloudstorage.insights;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class InsightsServiceTest {

    /** A clock the test can move forward. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-10-11T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** A scriptable AI: counts calls and answers (or fails) as told. */
    private static final class FakeAi implements AiClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<String> lastUserPrompt = new AtomicReference<>();
        boolean available = true;
        String answer = "{}";
        RuntimeException failure;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String modelName() {
            return "test-model";
        }

        @Override
        public String complete(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            lastUserPrompt.set(userPrompt);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private static StorageSnapshot snapshot(long downloads, long coldRestores) {
        return new StorageSnapshot(12, 7, 5, 6_384_480, 2_115_024, downloads, coldRestores, 12.0, 0.4,
                Instant.parse("2026-10-11T09:00:00Z"), 5, 0);
    }

    private final TestClock clock = new TestClock();
    private final FakeAi ai = new FakeAi();

    private InsightsService service(StorageSnapshot... sequence) {
        int[] next = {0};
        return new InsightsService(
                () -> sequence[Math.min(next[0]++, sequence.length - 1)], ai, clock);
    }

    @Test
    void anAiAnswerInJsonBecomesASummaryAndSuggestions() {
        ai.answer = "{\"summary\":\"Twelve files, five archived.\",\"suggestions\":[\"Keep the optimizer on.\",\"Watch restores.\",\"Review quotas.\"]}";

        InsightsReport report = service(snapshot(35, 1)).summarise();

        assertThat(report.source()).isEqualTo(InsightsReport.Source.AI);
        assertThat(report.model()).isEqualTo("test-model");
        assertThat(report.summary()).isEqualTo("Twelve files, five archived.");
        assertThat(report.suggestions()).containsExactly("Keep the optimizer on.", "Watch restores.", "Review quotas.");
        assertThat(report.note()).isNull();
    }

    @Test
    void anAnswerWrappedInACodeFenceIsStillUnderstood() {
        ai.answer = "```json\n{\"summary\":\"All good.\",\"suggestions\":[\"One.\"]}\n```";

        InsightsReport report = service(snapshot(35, 1)).summarise();

        assertThat(report.summary()).isEqualTo("All good.");
        assertThat(report.suggestions()).containsExactly("One.");
    }

    @Test
    void anAnswerThatIsNotJsonIsShownAsTheSummary() {
        ai.answer = "Storage looks healthy overall.";

        InsightsReport report = service(snapshot(35, 1)).summarise();

        assertThat(report.source()).isEqualTo(InsightsReport.Source.AI);
        assertThat(report.summary()).isEqualTo("Storage looks healthy overall.");
        assertThat(report.suggestions()).isEmpty();
    }

    @Test
    void whenTheAiFailsTheBuiltInSummaryIsShownAndTheReportSaysSo() {
        ai.failure = new IllegalStateException("timeout");

        InsightsReport report = service(snapshot(35, 1)).summarise();

        assertThat(report.source()).isEqualTo(InsightsReport.Source.BUILT_IN);
        assertThat(report.model()).isNull();
        assertThat(report.note()).contains("could not be reached");
        assertThat(report.summary()).contains("12 files").contains("5 archived");
    }

    @Test
    void withoutAnApiKeyTheAiIsNeverCalled() {
        ai.available = false;

        InsightsReport report = service(snapshot(35, 1)).summarise();

        assertThat(ai.calls).hasValue(0);
        assertThat(report.source()).isEqualTo(InsightsReport.Source.BUILT_IN);
        assertThat(report.note()).contains("No AI API key");
    }

    @Test
    void theSameNumbersAreAnsweredFromMemoryUntilTheyChangeOrFiveMinutesPass() {
        ai.answer = "{\"summary\":\"S\",\"suggestions\":[\"a\"]}";
        InsightsService service = service(snapshot(35, 1), snapshot(35, 1), snapshot(35, 1), snapshot(36, 1));

        service.summarise();
        service.summarise();
        assertThat(ai.calls).hasValue(1);

        clock.advance(Duration.ofMinutes(6));
        service.summarise();
        assertThat(ai.calls).hasValue(2);

        service.summarise(); // different numbers
        assertThat(ai.calls).hasValue(3);
    }

    @Test
    void theStatisticsSentToTheAiContainNoTextOnlyNumbers() {
        // If a text field were ever added to the snapshot, a file or folder name could reach the AI.
        assertThat(Arrays.stream(StorageSnapshot.class.getRecordComponents()).map(RecordComponent::getType))
                .allSatisfy(type -> assertThat(type == String.class || CharSequence.class.isAssignableFrom(type))
                        .as("snapshot must not contain text").isFalse());

        ai.answer = "{\"summary\":\"S\",\"suggestions\":[]}";
        service(snapshot(35, 1)).summarise();

        assertThat(ai.lastUserPrompt.get())
                .contains("files: 12 (hot: 7, cold/archived: 5)")
                .contains("downloads: 35")
                .contains("waited for an archived file to be restored: 1")
                .doesNotContain("@")
                .doesNotContain(".csv");
    }

    @Test
    void theBuiltInSuggestionsFollowTheNumbersAndNeverExceedThree() {
        StorageSnapshot manyRestores = new StorageSnapshot(40, 30, 10, 1_000_000, 900_000, 100, 40, 900.0, 0.4, null, 0, 0);

        var suggestions = BuiltInSummarizer.suggestions(manyRestores);

        assertThat(suggestions).hasSizeLessThanOrEqualTo(3);
        assertThat(String.join(" ", suggestions))
                .contains("has not run")                 // optimizer never ran
                .contains("40% of downloads waited");    // restore rate is high
    }

    private static StorageSnapshot withCluster(int nodes, int online, long underReplicated, int canFail, long pending) {
        return new StorageSnapshot(12, 7, 5, 6_384_480, 2_115_024, 35, 1, 12.0, 0.4,
                Instant.parse("2026-10-11T09:00:00Z"), 5, 0,
                nodes, online, 3, 33, underReplicated, canFail, 4, pending, 1_073_741_824L);
    }

    @Test
    void theRichAnswerKeepsTheStatusHeadlineFindingsAndSuggestions() {
        ai.answer = """
                {"status":"attention","headline":"One node is down.","summary":"Two nodes are left with spare capacity.",
                 "findings":["Redundancy: 4 of 5 nodes serving.","Performance: 12 ms to start."],
                 "suggestions":["Bring the node back - 4 of 5 serve - restores full safety."]}""";

        InsightsReport report = service(withCluster(5, 4, 0, 1, 0)).summarise();

        assertThat(report.status()).isEqualTo("ATTENTION");
        assertThat(report.headline()).isEqualTo("One node is down.");
        assertThat(report.findings()).hasSize(2);
        assertThat(report.suggestions()).hasSize(1);
    }

    @Test
    void anUnknownStatusIsDropped() {
        ai.answer = "{\"status\":\"great\",\"summary\":\"S\",\"suggestions\":[]}";

        assertThat(service(snapshot(35, 1)).summarise().status()).isNull();
    }

    @Test
    void theClusterAndAccountNumbersAreSentWhenKnown() {
        ai.answer = "{\"summary\":\"S\"}";
        service(withCluster(5, 4, 2, 1, 1)).summarise();

        assertThat(ai.lastUserPrompt.get())
                .contains("nodes: 5 (serving: 4)")
                .contains("replication factor): 3")
                .contains("chunks with fewer copies than wanted: 2")
                .contains("accounts: 4 (waiting for approval: 1)")
                .contains("share of files archived: 41.7%")
                .doesNotContain("@")
                .doesNotContain(".csv");
    }

    @Test
    void theBuiltInStatusFollowsRedundancyAndPerformance() {
        assertThat(BuiltInSummarizer.status(withCluster(5, 5, 0, 2, 0))).isEqualTo("OK");
        assertThat(BuiltInSummarizer.status(withCluster(5, 4, 0, 1, 0))).isEqualTo("ATTENTION");
        assertThat(BuiltInSummarizer.status(withCluster(5, 3, 7, 0, 0))).isEqualTo("CRITICAL");
        assertThat(BuiltInSummarizer.headline(withCluster(5, 4, 0, 1, 0))).contains("1 of 5 storage nodes are not serving");
        assertThat(BuiltInSummarizer.findings(withCluster(5, 5, 0, 2, 1)))
                .anyMatch(f -> f.startsWith("Redundancy:"))
                .anyMatch(f -> f.contains("1 of 4 are waiting for approval"));
    }
}
