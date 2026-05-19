package com.example;

import io.testlookup.TestLookupReporter;
import io.testlookup.TestLookupReporter.LiveSession;
import io.testlookup.TestLookupReporter.RecordOptions;
import io.testlookup.TestLookupReporter.SessionOptions;
import io.testlookup.TestLookupReporter.TestStatus;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Volume replication: 1000 cases — 149 failures, 10 skipped, 841 passed.
 * <p>
 * Failure and skip indices are precomputed deterministically so re-runs
 * produce the same pass/fail/skip distribution (useful for diffing the
 * server-side AI analysis between runs).
 * <p>
 * Per-case Thread.sleep is capped at {@link #MAX_SLEEP_MS} so the full run
 * lands in ~100 seconds wall-clock. The reported {@code durationMs} stays
 * realistic (300ms-30s) so the dashboard's latency view still looks like a
 * full CI suite.
 */
public class Volume1000WithFailuresAndSkipsTest {

    private static final int TOTAL_CASES         = 1000;
    private static final int EXPECTED_FAILURES   = 149;
    private static final int EXPECTED_SKIPS      = 10;
    private static final long MAX_SLEEP_MS       = 100;
    private static final int PROGRESS_EVERY      = 100;
    private static final String SUITE_LABEL      = "Volume1000WithFailuresAndSkipsTest";

    private static final Set<Integer> SKIPPED_INDICES;
    private static final Set<Integer> FAILED_INDICES;
    static {
        // Skipped: every 100th case — 100, 200, ..., 1000 → exactly 10.
        Set<Integer> skipped = new HashSet<>();
        for (int j = 100; j <= TOTAL_CASES; j += 100) skipped.add(j);
        SKIPPED_INDICES = Collections.unmodifiableSet(skipped);

        // Failed: 149 indices evenly spread across the 990 non-skipped cases.
        // step ≈ 6.644, so floor(k * step) for k = 0..148 produces 149 distinct values.
        List<Integer> nonSkipped = new ArrayList<>(TOTAL_CASES - skipped.size());
        for (int i = 1; i <= TOTAL_CASES; i++) {
            if (!skipped.contains(i)) nonSkipped.add(i);
        }
        Set<Integer> failed = new HashSet<>();
        double step = (double) nonSkipped.size() / EXPECTED_FAILURES;
        for (int k = 0; k < EXPECTED_FAILURES; k++) {
            failed.add(nonSkipped.get((int) Math.floor(k * step)));
        }
        FAILED_INDICES = Collections.unmodifiableSet(failed);

        // Belt-and-braces: the static set sizes are part of the contract, so
        // surface a startup failure here rather than a confusing test assertion
        // mismatch deep in the run.
        if (FAILED_INDICES.size() != EXPECTED_FAILURES) {
            throw new IllegalStateException(
                "FAILED_INDICES size=" + FAILED_INDICES.size()
                + " expected=" + EXPECTED_FAILURES);
        }
        if (SKIPPED_INDICES.size() != EXPECTED_SKIPS) {
            throw new IllegalStateException(
                "SKIPPED_INDICES size=" + SKIPPED_INDICES.size()
                + " expected=" + EXPECTED_SKIPS);
        }
    }

    @Test
    public void run1000CasesWith149FailuresAnd10Skipped() throws Exception {
        TestLookupReporter reporter = new TestLookupReporter.Builder()
            .framework("testng-programmatic")
            .build();
        String buildId = "volume-1000-mixed-" + UUID.randomUUID().toString().substring(0, 8);

        VolumeSummary.logHeader(SUITE_LABEL, buildId, TOTAL_CASES, EXPECTED_FAILURES, EXPECTED_SKIPS);
        long startMs = System.currentTimeMillis();

        LiveSession session = reporter.startSession(
            SessionOptions.builder()
                .buildNumber(buildId)
                .launchName("Volume - 1000 cases / 149 failed / 10 skipped")
                .releaseName("volume-" + buildId)
                .totalTests(TOTAL_CASES)
                .build()
        );

        int passed = 0, failed = 0, skipped = 0;
        try {
            for (int i = 1; i <= TOTAL_CASES; i++) {
                long durationMs   = realisticDurationMs(i);
                boolean isSkipped = SKIPPED_INDICES.contains(i);
                boolean isFailed  = FAILED_INDICES.contains(i);

                // Skipped tests shouldn't burn wall-clock; they're decided up
                // front and never "run" in real frameworks either.
                if (!isSkipped) {
                    Thread.sleep(Math.min(durationMs, MAX_SLEEP_MS));
                }

                String testName = caseName(i);
                RecordOptions.Builder opts = RecordOptions.builder()
                    .suiteName("volume-1000-mixed")
                    .className("VolumeMixed1000")
                    .tags(Arrays.asList("volume", "mixed", "size-1000"))
                    .metadata(metadataFor(i, isFailed, isSkipped));

                if (isSkipped) {
                    // Skipped events have no error/stack — just a reason in metadata.
                    session.record(testName, TestStatus.SKIPPED, 0L, opts.build());
                    skipped++;
                } else if (isFailed) {
                    opts.error("AssertionError: expected_count <= actual_count, got mismatch")
                        .stackTrace(testName + ": invariant violated in then_state_is_consistent");
                    session.record(testName, TestStatus.FAILED, durationMs, opts.build());
                    failed++;
                } else {
                    session.record(testName, TestStatus.PASSED, durationMs, opts.build());
                    passed++;
                }

                if (i % PROGRESS_EVERY == 0) {
                    VolumeSummary.logProgress(SUITE_LABEL, i, TOTAL_CASES, passed, failed, skipped);
                }
            }
        } finally {
            session.close();
        }

        long wallMs = System.currentTimeMillis() - startMs;
        VolumeSummary.logSummary(SUITE_LABEL, buildId, session, passed, failed, skipped, wallMs);

        Assert.assertEquals(passed,  TOTAL_CASES - EXPECTED_FAILURES - EXPECTED_SKIPS,
            "passed count mismatch");
        Assert.assertEquals(failed,  EXPECTED_FAILURES, "failure count mismatch");
        Assert.assertEquals(skipped, EXPECTED_SKIPS,    "skipped count mismatch");
        Assert.assertTrue(session.getSentCount() >= TOTAL_CASES,
            "all " + TOTAL_CASES + " events should be accepted (got " + session.getSentCount() + ")");
    }

    private static String caseName(int i) {
        // 10 domains x 10 actions x 10 contexts = 1000 unique combos.
        String[] domains = {"auth", "checkout", "search", "catalog", "orders",
                            "payments", "shipping", "users", "promotions", "inventory"};
        String[] actions = {"create", "update", "delete", "list", "validate",
                            "lookup", "audit", "reconcile", "sync", "expire"};
        String[] contexts = {"happy_path", "edge_case", "error_path", "concurrent",
                             "high_load", "boundary", "permission_denied", "stale_data",
                             "race_condition", "idempotency"};
        int idx = i - 1;
        int d = idx % domains.length;
        int a = (idx / domains.length) % actions.length;
        int c = (idx / (domains.length * actions.length)) % contexts.length;
        return String.format("%s_%s_%s_%04d", domains[d], actions[a], contexts[c], i);
    }

    private static long realisticDurationMs(int i) {
        int bucket = i % 50;
        if (bucket < 35) return 200 + (i * 53L) % 600;    // 200-800ms (~70%)
        if (bucket < 47) return 1200 + (i * 71L) % 1800;  // 1.2-3s    (~24%)
        if (bucket < 49) return 5000 + (i * 97L) % 5000;  // 5-10s     (~4%)
        return 15000 + (i * 31L) % 8000;                  // 15-23s    (~2%)
    }

    private static Map<String, Object> metadataFor(int i, boolean isFailed, boolean isSkipped) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_id", String.format("VOL-1000-%04d", i));
        if (isSkipped) {
            metadata.put("skip_reason", "blocked by feature flag CHECKOUT_V2 disabled in this env");
        } else if (isFailed) {
            metadata.put("expected_result", "downstream state is consistent after the call");
            metadata.put("actual_result", "expected_count != actual_count after operation");
            metadata.put("failed_step", "then_state_is_consistent");
        } else {
            metadata.put("expected_result", "operation completes and downstream state validates");
        }
        return metadata;
    }
}
