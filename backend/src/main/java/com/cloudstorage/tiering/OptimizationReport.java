package com.cloudstorage.tiering;

import java.time.Instant;

/**
 * Outcome of one optimizer run.
 *
 * @param model            which predictor made the decisions
 * @param trainingSamples  number of historical examples the model was trained on (0 for the baseline)
 * @param filesEvaluated   how many files were scored
 * @param movedToCold      files archived during this run
 * @param movedToHot       files promoted back to hot storage during this run
 */
public record OptimizationReport(
        Instant ranAt,
        String model,
        int trainingSamples,
        int filesEvaluated,
        int movedToCold,
        int movedToHot) {
}
