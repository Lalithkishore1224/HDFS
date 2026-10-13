package com.cloudstorage.insights;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Writes the same kind of summary from the same numbers without any AI. It is used when no API key is
 * configured or the AI cannot be reached, so the admin page always has something truthful to show.
 */
final class BuiltInSummarizer {

    private static final long KIB = 1024;

    private BuiltInSummarizer() {
    }

    static String summary(StorageSnapshot s) {
        if (s.fileCount() == 0) {
            return "There are no files in the system yet, so there is nothing to summarise.";
        }
        StringBuilder text = new StringBuilder();
        text.append(String.format(Locale.ROOT,
                "The system holds %d files: %d in fast (hot) storage and %d archived (cold). ",
                s.fileCount(), s.hotFileCount(), s.coldFileCount()));
        text.append(String.format(Locale.ROOT,
                "Together they are %s as users see them but occupy %s on disk, saving %s (%.0f%%). ",
                bytes(s.logicalBytes()), bytes(s.storedBytes()), bytes(s.savedBytes()), s.savedPercent()));
        if (s.downloads() == 0) {
            text.append("No files have been opened yet.");
        } else {
            text.append(String.format(Locale.ROOT,
                    "Of %d downloads, %d (%.0f%%) had to wait for an archived file to be restored, and a download starts in about %.0f ms on average.",
                    s.downloads(), s.coldRestores(), s.coldRestorePercent(), s.averageResponseMs()));
        }
        return text.toString();
    }

    static List<String> suggestions(StorageSnapshot s) {
        List<String> advice = new ArrayList<>();
        if (!s.optimizerHasRun()) {
            advice.add("The storage optimizer has not run since the server started. Run it from this page to archive files that are no longer used.");
        }
        if (s.downloads() > 0 && s.coldRestorePercent() > 10) {
            advice.add(String.format(Locale.ROOT,
                    "%.0f%% of downloads waited on a restore, which means archived files are still being used. Lowering the optimizer threshold keeps more files in fast storage.",
                    s.coldRestorePercent()));
        }
        if (s.fileCount() > 0 && s.coldFileCount() == 0 && s.optimizerHasRun()) {
            advice.add("No files are archived yet. Files are only archived once they have been idle for a while, so this is expected on a new system.");
        }
        if (s.fileCount() >= 10 && s.savedPercent() < 10 && s.optimizerHasRun()) {
            advice.add("Little space is being saved, so most files are in regular use. Archiving is not the best lever here; check each person's quota instead.");
        }
        if (s.savedPercent() >= 50) {
            advice.add(String.format(Locale.ROOT,
                    "Archiving is paying off, saving %.0f%% of the space. Keep the nightly optimizer enabled.", s.savedPercent()));
        }
        if (s.downloads() > 0 && s.averageResponseMs() > 500) {
            advice.add(String.format(Locale.ROOT,
                    "Downloads take %.0f ms to start on average, which is slow. Check disk speed and whether large archived files are being restored often.",
                    s.averageResponseMs()));
        }
        if (advice.isEmpty()) {
            advice.add("Nothing needs attention right now. Review these numbers again after more files have been used.");
        }
        return advice.size() > 3 ? List.copyOf(advice.subList(0, 3)) : List.copyOf(advice);
    }

    private static String bytes(long value) {
        if (value < KIB) {
            return value + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double size = value;
        int unit = -1;
        while (size >= KIB && unit < units.length - 1) {
            size /= KIB;
            unit++;
        }
        return String.format(Locale.ROOT, size >= 10 ? "%.0f %s" : "%.1f %s", size, units[unit]);
    }
}
