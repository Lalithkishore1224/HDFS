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


    /** OK, ATTENTION or CRITICAL, decided from the numbers alone. */
    static String status(StorageSnapshot s) {
        if (s.clusterKnown() && (s.nodesOnline() == 0 || s.nodesThatCanFail() == 0 && s.nodesOnline() < s.storageNodes())) {
            return "CRITICAL";
        }
        boolean redundancyReduced = s.clusterKnown()
                && (s.nodesOnline() < s.storageNodes() || s.underReplicatedChunks() > 0);
        boolean slow = s.downloads() > 0 && (s.averageResponseMs() > 500 || s.coldRestorePercent() > 10);
        return redundancyReduced || slow ? "ATTENTION" : "OK";
    }

    /** The one sentence that matters most. */
    static String headline(StorageSnapshot s) {
        if (s.fileCount() == 0) {
            return "The system is empty: there is nothing to analyse yet.";
        }
        if (s.clusterKnown() && s.nodesOnline() < s.storageNodes()) {
            return String.format(Locale.ROOT,
                    "%d of %d storage nodes are not serving; %d more could fail before files become unreadable.",
                    s.storageNodes() - s.nodesOnline(), s.storageNodes(), s.nodesThatCanFail());
        }
        if (s.clusterKnown() && s.underReplicatedChunks() > 0) {
            return String.format(Locale.ROOT, "%d chunks have fewer copies than wanted and are being re-copied.",
                    s.underReplicatedChunks());
        }
        if (s.downloads() > 0 && s.coldRestorePercent() > 10) {
            return String.format(Locale.ROOT, "%.0f%% of downloads waited on an archived file being restored.",
                    s.coldRestorePercent());
        }
        if (s.clusterKnown()) {
            return String.format(Locale.ROOT, "Storage is healthy: %d of %d nodes serving, %d copies of every chunk.",
                    s.nodesOnline(), s.storageNodes(), s.replicationFactor());
        }
        return "Storage looks healthy.";
    }

    /** Facts from the numbers: redundancy, performance, efficiency and accounts. */
    static List<String> findings(StorageSnapshot s) {
        List<String> facts = new ArrayList<>();
        if (s.fileCount() == 0) {
            return facts;
        }
        if (s.clusterKnown()) {
            facts.add(String.format(Locale.ROOT,
                    "Redundancy: %d of %d nodes are serving, every chunk is kept in %d copies, and %d more node(s) could fail without losing access to any file.",
                    s.nodesOnline(), s.storageNodes(), s.replicationFactor(), s.nodesThatCanFail()));
        }
        if (s.downloads() > 0) {
            facts.add(String.format(Locale.ROOT,
                    "Performance: a download starts in about %.0f ms, and %.0f%% of %d downloads waited for an archived file to be restored.",
                    s.averageResponseMs(), s.coldRestorePercent(), s.downloads()));
        }
        facts.add(String.format(Locale.ROOT,
                "Efficiency: %.0f%% of files (%d of %d) are archived, saving %s (%.0f%%) of disk space.",
                s.coldFilePercent(), s.coldFileCount(), s.fileCount(), bytes(s.savedBytes()), s.savedPercent()));
        facts.add(s.optimizerHasRun()
                ? String.format(Locale.ROOT, "The optimizer last archived %d and promoted %d files.",
                        s.lastRunMovedToCold(), s.lastRunMovedToHot())
                : "The optimizer has not run since the server started, so no files have been archived by it.");
        if (s.accountsKnown()) {
            facts.add(s.pendingAccounts() > 0
                    ? String.format(Locale.ROOT, "Accounts: %d of %d are waiting for approval.", s.pendingAccounts(), s.accounts())
                    : String.format(Locale.ROOT, "Accounts: %d, none waiting for approval.", s.accounts()));
        }
        return List.copyOf(facts);
    }

    static List<String> suggestions(StorageSnapshot s) {
        List<String> advice = new ArrayList<>();
        if (s.clusterKnown() && s.nodesOnline() < s.storageNodes()) {
            advice.add(String.format(Locale.ROOT,
                    "%d of %d storage nodes are not serving, so only %d more could fail before files become unreadable. Bring them back in the Storage nodes panel to restore full redundancy.",
                    s.storageNodes() - s.nodesOnline(), s.storageNodes(), s.nodesThatCanFail()));
        }
        if (s.clusterKnown() && s.underReplicatedChunks() > 0) {
            advice.add(String.format(Locale.ROOT,
                    "%d chunks have fewer copies than the %d wanted. Repair runs by itself, but you can start it with Repair copies.",
                    s.underReplicatedChunks(), s.replicationFactor()));
        }
        if (s.accountsKnown() && s.pendingAccounts() > 0) {
            advice.add(String.format(Locale.ROOT,
                    "%d account(s) are waiting for approval. Review them in the Accounts tab so people can start using the drive.",
                    s.pendingAccounts()));
        }
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
