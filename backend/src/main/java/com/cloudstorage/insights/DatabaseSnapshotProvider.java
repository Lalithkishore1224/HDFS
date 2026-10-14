package com.cloudstorage.insights;

import com.cloudstorage.account.AccountService;
import com.cloudstorage.account.AccountStatus;
import com.cloudstorage.stats.StatsService;
import com.cloudstorage.storage.ReplicatedFileStore;
import com.cloudstorage.tiering.OptimizationReport;
import com.cloudstorage.tiering.TieringService;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Reads the numbers the admin Storage tab already shows: statistics, the last optimizer run, the state of
 * the storage nodes and how many accounts there are. Only counts; never names or addresses.
 */
@Component
class DatabaseSnapshotProvider implements StorageSnapshotProvider {

    private final StatsService stats;
    private final TieringService tiering;
    private final ReplicatedFileStore store;
    private final AccountService accounts;

    DatabaseSnapshotProvider(StatsService stats, TieringService tiering, ReplicatedFileStore store, AccountService accounts) {
        this.stats = stats;
        this.tiering = tiering;
        this.store = store;
        this.accounts = accounts;
    }

    @Override
    public StorageSnapshot current() {
        StatsService.Stats totals = stats.current();
        Optional<OptimizationReport> lastRun = tiering.latestReport();
        ReplicatedFileStore.ClusterStatus cluster = store.status();
        long online = cluster.nodes().stream().filter(ReplicatedFileStore.NodeStatus::online).count();
        long pending = accounts.list(AccountStatus.PENDING).size();
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
                lastRun.map(OptimizationReport::movedToHot).orElse(0),
                cluster.nodes().size(),
                (int) online,
                cluster.replicationFactor(),
                cluster.chunks(),
                cluster.underReplicatedChunks(),
                cluster.nodesThatCanFail(),
                accounts.list(null).size(),
                pending,
                totals.quotaBytes());
    }
}
