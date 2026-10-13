package com.cloudstorage.optimizer;

import com.cloudstorage.file.StoredFile;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * What the optimizer knows about a file at a given moment in time.
 *
 * @param accessesLast30Days number of downloads in the 30 days before the snapshot
 * @param daysSinceLastAccess days since the last download (or since upload if never downloaded)
 * @param sizeMegabytes       original file size
 * @param ageDays             days since upload
 */
public record FileFeatures(double accessesLast30Days, double daysSinceLastAccess, double sizeMegabytes, double ageDays) {

    private static final Duration RECENT_WINDOW = Duration.ofDays(30);
    private static final double BYTES_PER_MEGABYTE = 1024.0 * 1024.0;

    /** Builds the features as they were at {@code asOf}, ignoring any access that happened later. */
    public static FileFeatures of(StoredFile file, List<Instant> accessTimes, Instant asOf) {
        Instant recentWindowStart = asOf.minus(RECENT_WINDOW);
        List<Instant> knownAccesses = accessTimes.stream().filter(time -> !time.isAfter(asOf)).toList();

        long recentAccesses = knownAccesses.stream().filter(time -> time.isAfter(recentWindowStart)).count();
        Instant lastAccess = knownAccesses.stream().max(Comparator.naturalOrder()).orElse(file.getUploadedAt());

        return new FileFeatures(
                recentAccesses,
                daysBetween(lastAccess, asOf),
                file.getSizeBytes() / BYTES_PER_MEGABYTE,
                daysBetween(file.getUploadedAt(), asOf));
    }

    /** The numeric vector fed to the model. Counts and sizes are log-scaled to tame outliers. */
    double[] toVector() {
        return new double[] {
                Math.log1p(accessesLast30Days),
                daysSinceLastAccess,
                Math.log1p(sizeMegabytes),
                ageDays
        };
    }

    private static double daysBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toSeconds() / 86_400.0);
    }
}
