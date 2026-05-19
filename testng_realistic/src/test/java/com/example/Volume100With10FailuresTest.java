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
 * Volume replication: 100 cases, exactly 10 failures, 90 passed.
 * <p>
 * Each case still gets a realistic {@code durationMs} reported to the server,
 * but per-case Thread.sleep is capped at {@link #MAX_SLEEP_MS} so the full
 * run lands in ~20-25 seconds of wall-clock instead of multiple minutes.
 * That keeps the streaming-cadence demo intact while making the test cheap
 * enough to replay on every commit.
 */
public class Volume100With10FailuresTest {

    private static final int TOTAL_CASES        = 100;
    private static final int EXPECTED_FAILURES  = 10;
    private static final int EXPECTED_SKIPS     = 0;
    private static final long MAX_SLEEP_MS      = 250;
    private static final int PROGRESS_EVERY     = 25;
    private static final String SUITE_LABEL     = "Volume100With10FailuresTest";

    @Test
    public void run100CasesWith10Failures() throws Exception {
        TestLookupReporter reporter = new TestLookupReporter.Builder()
            .framework("testng-programmatic")
            .build();
        String buildId = "volume-100-10f-" + UUID.randomUUID().toString().substring(0, 8);

        VolumeSummary.logHeader(SUITE_LABEL, buildId, TOTAL_CASES, EXPECTED_FAILURES, EXPECTED_SKIPS);
        long startMs = System.currentTimeMillis();

        LiveSession session = reporter.startSession(
            SessionOptions.builder()
                .buildNumber(buildId)
                .launchName("Volume - 100 cases / 10 failures")
                .releaseName("volume-" + buildId)
                .totalTests(TOTAL_CASES)
                .build()
        );

        int passed = 0;
        int failed = 0;
        try {
            for (int i = 1; i <= TOTAL_CASES; i++) {
                boolean shouldFail = isFailure(i);
                long durationMs    = realisticDurationMs(i);

                Thread.sleep(Math.min(durationMs, MAX_SLEEP_MS));

                String testName = caseName(i);
                RecordOptions.Builder opts = RecordOptions.builder()
                    .suiteName("volume-100-regression")
                    .className("VolumeRegression100")
                    .tags(Arrays.asList("volume", "regression", "size-100"))
                    .metadata(metadataFor(i, shouldFail));

                if (shouldFail) {
                    opts.error("AssertionError: response.status expected 200 but was 500")
                        .stackTrace(testName + ": api.response.status assertion failed");
                    session.record(testName, TestStatus.FAILED, durationMs, opts.build());
                    failed++;
                } else {
                    session.record(testName, TestStatus.PASSED, durationMs, opts.build());
                    passed++;
                }

                if (i % PROGRESS_EVERY == 0) {
                    VolumeSummary.logProgress(SUITE_LABEL, i, TOTAL_CASES, passed, failed, 0);
                }
            }
        } finally {
            session.close();
        }

        long wallMs = System.currentTimeMillis() - startMs;
        VolumeSummary.logSummary(SUITE_LABEL, buildId, session, passed, failed, 0, wallMs);

        Assert.assertEquals(failed, EXPECTED_FAILURES,
            "should have produced exactly " + EXPECTED_FAILURES + " failures");
        Assert.assertEquals(passed, TOTAL_CASES - EXPECTED_FAILURES,
            "passed count mismatch");
        Assert.assertTrue(session.getSentCount() >= TOTAL_CASES,
            "all " + TOTAL_CASES + " cases should be accepted (got " + session.getSentCount() + ")");
    }

    /** Every 10th case fails (i = 10, 20, ..., 100), yielding exactly 10 failures. */
    private static boolean isFailure(int i) {
        return i % (TOTAL_CASES / EXPECTED_FAILURES) == 0;
    }

    private static String caseName(int i) {
        String[] domains = {"auth", "checkout", "search", "catalog", "orders",
                            "payments", "shipping", "users", "promotions", "inventory"};
        String[] actions = {"create", "update", "delete", "list", "validate",
                            "lookup", "audit", "reconcile", "sync", "expire"};
        return String.format("%s_%s_case_%03d",
            domains[i % domains.length],
            actions[(i / domains.length) % actions.length],
            i);
    }

    private static long realisticDurationMs(int i) {
        int bucket = i % 50;
        if (bucket < 30) return 300 + (i * 53L) % 700;    // 300-1000ms (~60%)
        if (bucket < 45) return 1500 + (i * 71L) % 2000;  // 1.5-3.5s   (~30%)
        if (bucket < 49) return 6000 + (i * 97L) % 6000;  // 6-12s      (~8%)
        return 20000 + (i * 31L) % 10000;                 // 20-30s     (~2%)
    }

    private static Map<String, Object> metadataFor(int i, boolean shouldFail) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_id", String.format("VOL-100-%03d", i));
        metadata.put("expected_result", "endpoint returns HTTP 200 with payload");
        if (shouldFail) {
            metadata.put("actual_result", "endpoint returned HTTP 500");
            metadata.put("failed_step", "then_response_is_2xx");
        }
        return metadata;
    }
}
