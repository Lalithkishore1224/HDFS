package com.cloudstorage.optimizer;

import java.util.List;

/**
 * A logistic regression trained with plain gradient descent.
 * <p>
 * Small on purpose: with a handful of numeric features this needs no ML library, and the whole
 * model is the few dozen lines below.
 */
public final class LogisticAccessPredictor implements AccessPredictor {

    private static final int ITERATIONS = 3_000;
    private static final double LEARNING_RATE = 0.1;
    private static final double L2_PENALTY = 0.01;

    private final double[] means;
    private final double[] deviations;
    private final double[] weights;
    private final double bias;

    private LogisticAccessPredictor(double[] means, double[] deviations, double[] weights, double bias) {
        this.means = means;
        this.deviations = deviations;
        this.weights = weights;
        this.bias = bias;
    }

    public static LogisticAccessPredictor train(List<TrainingSample> samples) {
        int featureCount = samples.getFirst().features().toVector().length;
        double[][] inputs = samples.stream().map(sample -> sample.features().toVector()).toArray(double[][]::new);
        double[] labels = samples.stream().mapToDouble(sample -> sample.accessedAfterwards() ? 1.0 : 0.0).toArray();

        double[] means = columnMeans(inputs, featureCount);
        double[] deviations = columnDeviations(inputs, means);
        double[][] standardized = standardize(inputs, means, deviations);

        double[] weights = new double[featureCount];
        double bias = 0;
        for (int iteration = 0; iteration < ITERATIONS; iteration++) {
            double[] weightGradient = new double[featureCount];
            double biasGradient = 0;
            for (int row = 0; row < standardized.length; row++) {
                double error = sigmoid(bias + dot(weights, standardized[row])) - labels[row];
                for (int feature = 0; feature < featureCount; feature++) {
                    weightGradient[feature] += error * standardized[row][feature];
                }
                biasGradient += error;
            }
            for (int feature = 0; feature < featureCount; feature++) {
                weights[feature] -= LEARNING_RATE
                        * (weightGradient[feature] / standardized.length + L2_PENALTY * weights[feature]);
            }
            bias -= LEARNING_RATE * biasGradient / standardized.length;
        }
        return new LogisticAccessPredictor(means, deviations, weights, bias);
    }

    @Override
    public String name() {
        return "logistic-regression";
    }

    @Override
    public double probabilityOfAccess(FileFeatures features) {
        double[] vector = features.toVector();
        double[] standardized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            standardized[i] = (vector[i] - means[i]) / deviations[i];
        }
        return sigmoid(bias + dot(weights, standardized));
    }

    private static double[] columnMeans(double[][] rows, int columns) {
        double[] means = new double[columns];
        for (double[] row : rows) {
            for (int c = 0; c < columns; c++) {
                means[c] += row[c] / rows.length;
            }
        }
        return means;
    }

    private static double[] columnDeviations(double[][] rows, double[] means) {
        double[] deviations = new double[means.length];
        for (double[] row : rows) {
            for (int c = 0; c < means.length; c++) {
                deviations[c] += Math.pow(row[c] - means[c], 2) / rows.length;
            }
        }
        for (int c = 0; c < deviations.length; c++) {
            double deviation = Math.sqrt(deviations[c]);
            deviations[c] = deviation > 1e-9 ? deviation : 1.0; // a constant column carries no signal
        }
        return deviations;
    }

    private static double[][] standardize(double[][] rows, double[] means, double[] deviations) {
        double[][] result = new double[rows.length][];
        for (int r = 0; r < rows.length; r++) {
            result[r] = new double[means.length];
            for (int c = 0; c < means.length; c++) {
                result[r][c] = (rows[r][c] - means[c]) / deviations[c];
            }
        }
        return result;
    }

    private static double dot(double[] a, double[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }
}
