package com.cloudstorage.tiering;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/optimizer")
class OptimizerController {

    private final TieringService tieringService;

    OptimizerController(TieringService tieringService) {
        this.tieringService = tieringService;
    }

    /** @param lastRun null until the optimizer has run at least once since start-up */
    record OptimizerStatus(double hotProbabilityThreshold, OptimizationReport lastRun) {
    }

    @GetMapping
    OptimizerStatus status() {
        return new OptimizerStatus(tieringService.hotProbabilityThreshold(), tieringService.latestReport().orElse(null));
    }

    @PostMapping("/run")
    OptimizationReport run() {
        return tieringService.optimize();
    }
}
