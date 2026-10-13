package com.cloudstorage.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning knobs for the tiering optimizer.
 *
 * @param hotProbabilityThreshold minimum predicted chance of access for a file to stay in hot storage
 * @param minTrainingSamples      fewer samples than this and the model is not trained
 * @param scheduleCron            when the optimizer runs automatically
 */
@ConfigurationProperties("optimizer")
public record OptimizerProperties(double hotProbabilityThreshold, int minTrainingSamples, String scheduleCron) {
}
