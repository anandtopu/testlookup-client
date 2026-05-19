package com.example;

import io.testlookup.TestLookupReporter.LiveSession;

/**
 * Shared logging helpers for the Volume* replication suites.
 * <p>
 * Output is plain {@code System.out.println} — Surefire captures it into the
 * per-test XML in {@code target/surefire-reports/} and also echoes it to the
 * terminal during {@code mvn test}, so the simulation shape is visible both
 * in CI logs and the report files. We deliberately do not use
 * {@code java.util.logging} here: the SDK's own JUL output is already noisy,
 * and a clean block-formatted summary is easier to scan when scanning a CI
 * log for "what did this run actually simulate?".
 */
final class VolumeSummary {

    private static final String BORDER  = "============================================================";
    private static final String DIVIDER = "------------------------------------------------------------";

    private VolumeSummary() {}

    /** Logged once before the simulation loop starts. */
    static void logHeader(String suiteName, String buildId,
                          int total, int plannedFailures, int plannedSkips) {
        int plannedPassed = total - plannedFailures - plannedSkips;
        System.out.println();
        System.out.println(BORDER);
        System.out.println("  " + suiteName + " — starting simulation");
        System.out.println(DIVIDER);
        System.out.printf ("  build_id           %s%n", buildId);
        System.out.printf ("  total_cases        %d%n", total);
        System.out.printf ("  planned passed     %d%n", plannedPassed);
        System.out.printf ("  planned failed     %d%n", plannedFailures);
        System.out.printf ("  planned skipped    %d%n", plannedSkips);
        System.out.println(BORDER);
    }

    /** Logged periodically from inside the simulation loop. */
    static void logProgress(String suiteName, int done, int total,
                            int passed, int failed, int skipped) {
        System.out.printf("  [%s] progress %d/%d  passed=%d  failed=%d  skipped=%d%n",
            suiteName, done, total, passed, failed, skipped);
    }

    /** Logged once after the session closes. Includes counts the SDK reports. */
    static void logSummary(String suiteName, String buildId, LiveSession session,
                           int passed, int failed, int skipped, long wallClockMs) {
        int total = passed + failed + skipped;
        System.out.println();
        System.out.println(BORDER);
        System.out.println("  " + suiteName + " — simulation summary");
        System.out.println(DIVIDER);
        System.out.printf ("  build_id           %s%n", buildId);
        System.out.printf ("  session_id         %s%n", session.getSessionId());
        System.out.printf ("  run_id             %s%n", session.getRunId());
        System.out.println(DIVIDER);
        System.out.printf ("  total_cases        %d%n", total);
        System.out.printf ("    PASSED           %d%n", passed);
        System.out.printf ("    FAILED           %d%n", failed);
        System.out.printf ("    SKIPPED          %d%n", skipped);
        System.out.println(DIVIDER);
        System.out.printf ("  events_sent        %d%n", session.getSentCount());
        System.out.printf ("  events_failed      %d%n", session.getFailedCount());
        System.out.printf ("  wall_clock         %s%n", formatDuration(wallClockMs));
        System.out.println(BORDER);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long totalSeconds = ms / 1000;
        long minutes      = totalSeconds / 60;
        long seconds      = totalSeconds % 60;
        long millis       = ms % 1000;
        if (minutes > 0) return String.format("%dm %d.%03ds", minutes, seconds, millis);
        return String.format("%d.%03ds", seconds, millis);
    }
}
