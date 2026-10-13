package com.cloudstorage.optimizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogisticAccessPredictorTest {

    @Test
    void learnsThatRecentlyAndFrequentlyUsedFilesAreLikelyToBeNeededAgain() {
        List<TrainingSample> samples = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            samples.add(new TrainingSample(new FileFeatures(6 + i % 3, 1 + i % 4, 1.0, 30), true));
            samples.add(new TrainingSample(new FileFeatures(i % 2, 40 + i, 1.0, 90), false));
        }

        LogisticAccessPredictor predictor = LogisticAccessPredictor.train(samples);

        double activeFile = predictor.probabilityOfAccess(new FileFeatures(7, 2, 1.0, 30));
        double idleFile = predictor.probabilityOfAccess(new FileFeatures(0, 60, 1.0, 90));

        assertThat(activeFile).isGreaterThan(0.8);
        assertThat(idleFile).isLessThan(0.2);
    }
}
