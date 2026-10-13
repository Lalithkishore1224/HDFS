package com.cloudstorage.tiering;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the optimizer on the configured schedule. */
@Component
class TieringScheduler {

    private static final Logger log = LoggerFactory.getLogger(TieringScheduler.class);

    private final TieringService tieringService;

    TieringScheduler(TieringService tieringService) {
        this.tieringService = tieringService;
    }

    @Scheduled(cron = "${optimizer.schedule-cron}")
    void runScheduledOptimization() {
        OptimizationReport report = tieringService.optimize();
        log.info("Scheduled optimization finished: {}", report);
    }
}
