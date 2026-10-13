package com.cloudstorage.insights;

import com.cloudstorage.stats.StatsService;
import com.cloudstorage.tiering.OptimizationReport;
import com.cloudstorage.tiering.TieringService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reads the numbers the admin Storage tab already shows: statistics plus the last optimizer run. */
@Component
class DatabaseSnapshotProvider implements StorageSnapshotProvider {

    private final StatsService stats;
    private final TieringService tiering;

    DatabaseSnapshotProvider(StatsService stats, TieringService tiering) {
        this.stats = stats;
        this.tiering = tiering;
    }

    @Override
    public StorageSnapshot current() {
        StatsService.Stats totals = stats.current();
        Optional<OptimizationReport> lastRun = tiering.latestReport();
        return new StorageSnapshot(
                totals.fileCount(),
                totals.hotFileCount(),
                totals.coldFileCount(),
                totals.logicalBytes(),
                totals.storedBytes(),
                totals.downloads(),
                totals.coldRestores(),
                totals.averageResponseMs(),
                tiering.hotProbabilityThreshold(),
                lastRun.map(OptimizationReport::ranAt).orElse(null),
                lastRun.map(OptimizationReport::movedToCold).orElse(0),
                lastRun.map(OptimizationReport::movedToHot).orElse(0));
    }
}
