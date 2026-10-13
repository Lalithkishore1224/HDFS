package com.cloudstorage.tiering;

import com.cloudstorage.access.AccessLog;
import com.cloudstorage.access.AccessLogRepository;
import com.cloudstorage.config.OptimizerProperties;
import com.cloudstorage.file.FileRepository;
import com.cloudstorage.file.StoredFile;
import com.cloudstorage.optimizer.AccessPredictor;
import com.cloudstorage.optimizer.FileFeatures;
import com.cloudstorage.optimizer.LogisticAccessPredictor;
import com.cloudstorage.optimizer.RecencyAccessPredictor;
import com.cloudstorage.optimizer.TrainingSample;
import com.cloudstorage.optimizer.TrainingSetBuilder;
import com.cloudstorage.storage.StorageTier;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides which files belong in hot storage and which can be archived.
 * <p>
 * Every run re-trains the model on the latest access history, scores every file, and moves
 * files whose predicted chance of access is on the wrong side of the configured threshold.
 */
@Service
public class TieringService {

    /** A fresh upload is never archived immediately, whatever the model says. */
    private static final Logger log = LoggerFactory.getLogger(TieringService.class);

    private static final Duration NEW_FILE_GRACE_PERIOD = Duration.ofDays(1);

    private final FileRepository files;
    private final AccessLogRepository accessLogs;
    private final TrainingSetBuilder trainingSetBuilder;
    private final RecencyAccessPredictor baselinePredictor;
    private final TierMover tierMover;
    private final OptimizerProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    private final AtomicReference<OptimizationReport> latestReport = new AtomicReference<>();

    public TieringService(FileRepository files, AccessLogRepository accessLogs, TrainingSetBuilder trainingSetBuilder,
                          RecencyAccessPredictor baselinePredictor, TierMover tierMover,
                          OptimizerProperties properties, Clock clock,
                          PlatformTransactionManager transactionManager) {
        this.files = files;
        this.accessLogs = accessLogs;
        this.trainingSetBuilder = trainingSetBuilder;
        this.baselinePredictor = baselinePredictor;
        this.tierMover = tierMover;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public Optional<OptimizationReport> latestReport() {
        return Optional.ofNullable(latestReport.get());
    }

    public double hotProbabilityThreshold() {
        return properties.hotProbabilityThreshold();
    }

    /** What one file ended up as: the prediction and where its bytes now are. */
    private record Outcome(UUID fileId, double probability, StorageTier tier, long storedSizeBytes) {
    }

    /** Everything the optimizer needs to read, taken in one short transaction. */
    private record Snapshot(List<StoredFile> files, Map<UUID, List<Instant>> accessHistory) {
    }

    /**
     * Runs in three steps so that no database transaction is open while files are compressed:
     * read a snapshot, move bytes (slow, no transaction), then record all results in one short write.
     */
    public synchronized OptimizationReport optimize() {
        Instant now = clock.instant();
        Snapshot snapshot = transactions.execute(status -> new Snapshot(files.findAll(), loadAccessHistory()));
        List<StoredFile> allFiles = snapshot.files();
        Map<UUID, List<Instant>> accessHistory = snapshot.accessHistory();

        List<TrainingSample> samples = trainingSetBuilder.build(allFiles, accessHistory, now);
        boolean modelTrainable = isTrainable(samples);
        AccessPredictor predictor = modelTrainable ? LogisticAccessPredictor.train(samples) : baselinePredictor;

        int movedToCold = 0;
        int movedToHot = 0;
        List<Outcome> outcomes = new ArrayList<>();
        for (StoredFile file : allFiles) {
            List<Instant> accesses = accessHistory.getOrDefault(file.getId(), List.of());
            double probability = predictor.probabilityOfAccess(FileFeatures.of(file, accesses, now));

            StorageTier tier = file.getTier();
            long storedSize = file.getStoredSizeBytes();
            StorageTier target = chooseTier(file, probability, now);
            if (target != file.getTier()) {
                try {
                    storedSize = tierMover.move(file.getId(), target);
                    tier = target;
                    if (target == StorageTier.COLD) {
                        movedToCold++;
                    } else {
                        movedToHot++;
                    }
                } catch (UncheckedIOException e) {
                    // The file was deleted while the optimizer was running; nothing to do for it.
                    log.warn("Skipping file {}: {}", file.getId(), e.getMessage());
                    continue;
                }
            }
            outcomes.add(new Outcome(file.getId(), probability, tier, storedSize));
        }

        transactions.executeWithoutResult(status -> outcomes.forEach(outcome ->
                files.findById(outcome.fileId()).ifPresent(current -> {
                    current.updateHotProbability(outcome.probability());
                    current.placeIn(outcome.tier(), outcome.storedSizeBytes());
                })));

        OptimizationReport report = new OptimizationReport(
                now, predictor.name(), modelTrainable ? samples.size() : 0, allFiles.size(), movedToCold, movedToHot);
        latestReport.set(report);
        return report;
    }

    private StorageTier chooseTier(StoredFile file, double probability, Instant now) {
        boolean isNew = Duration.between(file.getUploadedAt(), now).compareTo(NEW_FILE_GRACE_PERIOD) < 0;
        boolean likelyNeeded = probability >= properties.hotProbabilityThreshold();
        return (isNew || likelyNeeded) ? StorageTier.HOT : StorageTier.COLD;
    }

    /** Training needs enough examples, and both outcomes must have occurred at least once. */
    private boolean isTrainable(List<TrainingSample> samples) {
        long positives = samples.stream().filter(TrainingSample::accessedAfterwards).count();
        return samples.size() >= properties.minTrainingSamples() && positives > 0 && positives < samples.size();
    }

    private Map<UUID, List<Instant>> loadAccessHistory() {
        return accessLogs.findAll().stream().collect(Collectors.groupingBy(
                AccessLog::getFileId,
                Collectors.mapping(AccessLog::getAccessedAt, Collectors.toList())));
    }
}
