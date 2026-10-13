package com.cloudstorage.optimizer;

import org.springframework.stereotype.Component;

/**
 * The fixed rule used while there is not enough history to train a model:
 * the longer a file has been idle, the less likely it is to be needed again.
 */
@Component
public class RecencyAccessPredictor implements AccessPredictor {

    private static final double HALF_LIFE_SCALE_DAYS = 14.0;

    @Override
    public String name() {
        return "recency-baseline";
    }

    @Override
    public double probabilityOfAccess(FileFeatures features) {
        return Math.exp(-features.daysSinceLastAccess() / HALF_LIFE_SCALE_DAYS);
    }
}
