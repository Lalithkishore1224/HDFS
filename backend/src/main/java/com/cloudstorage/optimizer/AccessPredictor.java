package com.cloudstorage.optimizer;

/** Estimates how likely a file is to be opened during the next week. */
public interface AccessPredictor {

    /** A short identifier shown in the UI, e.g. "logistic-regression". */
    String name();

    /** @return a probability between 0 (certainly idle) and 1 (certainly needed) */
    double probabilityOfAccess(FileFeatures features);
}
