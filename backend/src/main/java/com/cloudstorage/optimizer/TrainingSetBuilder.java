package com.cloudstorage.optimizer;

import com.cloudstorage.file.StoredFile;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns the access history into supervised examples by "going back in time":
 * for a past snapshot date, describe each file as it was then and check whether it was opened
 * in the following week. Two snapshots (one and two weeks ago) are used so a young system
 * still gets a useful number of examples.
 */
@Component
public class TrainingSetBuilder {

    private static final Duration LABEL_WINDOW = Duration.ofDays(7);
    private static final int SNAPSHOT_COUNT = 2;

    public List<TrainingSample> build(List<StoredFile> files, Map<UUID, List<Instant>> accessHistory, Instant now) {
        List<TrainingSample> samples = new ArrayList<>();
        for (int weeksAgo = 1; weeksAgo <= SNAPSHOT_COUNT; weeksAgo++) {
            Instant snapshot = now.minus(LABEL_WINDOW.multipliedBy(weeksAgo));
            Instant labelWindowEnd = snapshot.plus(LABEL_WINDOW);

            for (StoredFile file : files) {
                if (file.getUploadedAt().isAfter(snapshot)) {
                    continue; // the file did not exist yet
                }
                List<Instant> accesses = accessHistory.getOrDefault(file.getId(), List.of());
                FileFeatures features = FileFeatures.of(file, accesses, snapshot);
                boolean accessedAfterwards = accesses.stream()
                        .anyMatch(time -> time.isAfter(snapshot) && !time.isAfter(labelWindowEnd));
                samples.add(new TrainingSample(features, accessedAfterwards));
            }
        }
        return samples;
    }
}
