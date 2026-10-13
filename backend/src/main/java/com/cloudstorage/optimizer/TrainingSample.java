package com.cloudstorage.optimizer;

/** One historical observation: what a file looked like, and whether it was opened in the week that followed. */
public record TrainingSample(FileFeatures features, boolean accessedAfterwards) {
}
