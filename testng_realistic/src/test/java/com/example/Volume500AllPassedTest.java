package com.example;

import io.testlookup.TestLookupReporter;
import io.testlookup.TestLookupReporter.LiveSession;
import io.testlookup.TestLookupReporter.RecordOptions;
import io.testlookup.TestLookupReporter.SessionOptions;
import io.testlookup.TestLookupReporter.TestStatus;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Volume replication: 500 cases, all passing — the happy-path baseline run.
 * <p>
 * Per-case Thread.sleep is capped at {@link #MAX_SLEEP_MS} so the full run
 * lands in ~75 seconds wall-clock. The reported {@code durationMs} stays in
 * the realistic 300ms-30s range so the dashboard's per-test latency view
 * looks like a real CI suite.
 */
public class Volume500AllPassedTest {

    private static final int TOTAL_CASES   = 500;
    private static final long MAX_SLEEP_MS = 150;
    private static final int PROGRESS_EVERY = 100;
    private static final String SUITE_LABEL = "Volume500AllPassedTest";

    @Test
    public void run500CasesAllPassed() throws Exception {
        TestLookupReporter reporter = new TestLookupReporter.Builder()
            .framework("testng-programmatic")
            .build();
        String buildId = "volume-500-pass-" + UUID.randomUUID().toString().substring(0, 8);

        VolumeSummary.logHeader(SUITE_LABEL, buildId, TOTAL_CASES, 0, 0);
        long startMs = System.currentTimeMillis();

        LiveSession session = reporter.startSession(
            SessionOptions.builder()
                .buildNumber(buildId)
                .launchName("Volume - 500 cases / all passed")
                .releaseName("volume-" + buildId)
                .totalTests(TOTAL_CASES)
                .build()
        );

        int passed = 0;
        try {
            for (int i = 1; i <= TOTAL_CASES; i++) {
                long durationMs = realisticDurationMs(i);
                Thread.sleep(Math.min(durationMs, MAX_SLEEP_MS));

                String testName = caseName(i);
                RecordOptions opts = RecordOptions.builder()
                    .suiteName("volume-500-happy-path")
                    .className("VolumeHappyPath500")
                    .tags(Arrays.asList("volume", "happy-path", "size-500"))
                    .metadata(metadataFor(i))
                    .build();

                session.record(testName, TestStatus.PASSED, durationMs, opts);
                passed++;

                if (i % PROGRESS_EVERY == 0) {
                    VolumeSummary.logProgress(SUITE_LABEL, i, TOTAL_CASES, passed, 0, 0);
                }
            }
        } finally {
            session.close();
        }

        long wallMs = System.currentTimeMillis() - startMs;
        VolumeSummary.logSummary(SUITE_LABEL, buildId, session, passed, 0, 0, wallMs);

        Assert.assertEquals(passed, TOTAL_CASES, "all cases should be recorded as passed");
        Assert.assertTrue(session.getSentCount() >= TOTAL_CASES,
            "all " + TOTAL_CASES + " cases should be accepted (got " + session.getSentCount() + ")");
        Assert.assertEquals(session.getFailedCount(), 0L,
            "no events should fail to flush in the happy-path run");
    }

    private static String caseName(int i) {
        // 10 domains x 10 actions x 5 contexts = 500 unique combos.
        String[] domains = {"auth", "checkout", "search", "catalog", "orders",
                            "payments", "shipping", "users", "promotions", "inventory"};
        String[] actions = {"create", "update", "delete", "list", "validate",
                            "lookup", "audit", "reconcile", "sync", "expire"};
        String[] contexts = {"happy_path", "edge_case", "concurrent", "boundary", "idempotency"};
        int d = (i - 1) % domains.length;
        int a = ((i - 1) / domains.length) % actions.length;
        int c = ((i - 1) / (domains.length * actions.length)) % contexts.length;
        return String.format("%s_%s_%s_%03d", domains[d], actions[a], contexts[c], i);
    }

    private static long realisticDurationMs(int i) {
        int bucket = i % 50;
        if (bucket < 35) return 200 + (i * 53L) % 600;    // 200-800ms (~70%)
        if (bucket < 47) return 1200 + (i * 71L) % 1800;  // 1.2-3s    (~24%)
        if (bucket < 49) return 5000 + (i * 97L) % 5000;  // 5-10s     (~4%)
        return 15000 + (i * 31L) % 8000;                  // 15-23s    (~2%)
    }

    private static Map<String, Object> metadataFor(int i) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_id", String.format("VOL-500-%03d", i));
        metadata.put("expected_result", "request succeeds and response payload validates");
        return metadata;
    }
}
