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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

public class ParallelSuitesWithOneApiKeyTest {

    /**
     * Four parallel suites; each suite's serial runtime is ~3-4 minutes so the
     * wall-clock for the whole test (max across suites) lands ~4 minutes —
     * leaving room for SingleSuiteHundredCasesTest (which runs sequentially
     * after this one in testng.xml) to also fit inside a 5-10 minute combined
     * run. Durations mix fast unit-style checks, mid-tier integration calls,
     * and the occasional long async flow.
     */
    private static final Map<String, List<SimulatedCase>> SUITES = buildSuites();

    private static Map<String, List<SimulatedCase>> buildSuites() {
        Map<String, List<SimulatedCase>> suites = new LinkedHashMap<>();
        suites.put("auth-api", Arrays.asList(
            new SimulatedCase("login_accepts_valid_credentials",       true,   1200),
            new SimulatedCase("login_rejects_locked_account",          true,    950),
            new SimulatedCase("refresh_token_rotates",                  true,   1800),
            new SimulatedCase("mfa_challenge_required_for_admin",      true,   4500),
            new SimulatedCase("password_reset_email_dispatched",       true,  12000),
            new SimulatedCase("rate_limiter_blocks_brute_force",       true,  30000),
            new SimulatedCase("device_fingerprint_recorded",            true,   3200),
            new SimulatedCase("logout_invalidates_all_sessions",       true,  18000),
            new SimulatedCase("api_key_scope_enforced",                 false, 22000),
            new SimulatedCase("social_login_links_existing_account",   true,  90000)
        ));
        suites.put("checkout-api", Arrays.asList(
            new SimulatedCase("cart_total_includes_tax",                true,   1300),
            new SimulatedCase("expired_coupon_is_rejected",             true,   1750),
            new SimulatedCase("inventory_decrement_is_atomic",          true,   8500),
            new SimulatedCase("currency_conversion_uses_daily_rate",   true,   2400),
            new SimulatedCase("split_shipment_recalculates_freight",   true,  16000),
            new SimulatedCase("payment_gateway_timeout_is_retried",    false,  35000),
            new SimulatedCase("idempotency_key_prevents_double_charge",true,   5500),
            new SimulatedCase("fraud_check_runs_for_high_value",       true,  25000),
            new SimulatedCase("end_to_end_guest_checkout",              true,  60000),
            new SimulatedCase("end_to_end_subscription_renewal",       true,  75000)
        ));
        suites.put("orders-ui", Arrays.asList(
            new SimulatedCase("order_history_loads",                    true,   2200),
            new SimulatedCase("order_detail_shows_tracking",            true,   3100),
            new SimulatedCase("cancel_button_hidden_after_ship",        true,   1900),
            new SimulatedCase("invoice_pdf_downloads",                  true,   8400),
            new SimulatedCase("address_edit_validates_postcode",       true,   4700),
            new SimulatedCase("returns_flow_creates_rma",               true,  35000),
            new SimulatedCase("filter_by_date_range_paginates",        false,  12000),
            new SimulatedCase("bulk_export_csv_streams",                true,  45000),
            new SimulatedCase("dark_mode_persists_across_reload",      true,   3300),
            new SimulatedCase("cross_browser_screenshot_diff",         true,  90000)
        ));
        suites.put("notifications", Arrays.asList(
            new SimulatedCase("email_receipt_sent",                     true,   2500),
            new SimulatedCase("sms_opt_out_is_honored",                 true,   1800),
            new SimulatedCase("webhook_signature_is_verified",          true,   1100),
            new SimulatedCase("push_token_refresh_is_propagated",      true,   8000),
            new SimulatedCase("digest_email_aggregates_24h",            true,  25000),
            new SimulatedCase("retry_queue_drains_after_outage",       true,  60000),
            new SimulatedCase("dead_letter_alert_fires",                false, 22000),
            new SimulatedCase("template_localization_renders",          true,   6500),
            new SimulatedCase("delivery_tracking_async_callback",      true,  90000)
        ));
        return suites;
    }

    @Test
    public void runMultipleSuitesInParallelWithOneApiKey() throws Exception {
        TestLookupReporter reporter = new TestLookupReporter.Builder()
            .framework("testng-programmatic")
            .build();
        String buildId = "testng-parallel-" + UUID.randomUUID().toString().substring(0, 8);

        ExecutorService executor = Executors.newFixedThreadPool(SUITES.size());
        try {
            List<Callable<Long>> jobs = SUITES.entrySet().stream()
                .<Callable<Long>>map(entry -> () -> runSuite(reporter, buildId, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

            long sent = 0;
            for (Future<Long> future : executor.invokeAll(jobs)) {
                sent += future.get();
            }

            long expected = SUITES.values().stream().mapToLong(List::size).sum();
            Assert.assertTrue(sent >= expected,
                "all parallel suite events should be accepted (got " + sent + " of " + expected + ")");
        } finally {
            executor.shutdownNow();
        }
    }

    private long runSuite(
        TestLookupReporter reporter,
        String buildId,
        String suiteName,
        List<SimulatedCase> cases
    ) throws Exception {
        LiveSession session = reporter.startSession(
            SessionOptions.builder()
                .buildNumber(buildId)
                .launchName("Parallel suites - " + buildId)
                .releaseName("parallel-" + suiteName + "-" + buildId)
                .totalTests(cases.size())
                .build()
        );
        try {
            for (SimulatedCase testCase : cases) {
                // Sleep the full reported duration so events stream into the live
                // UI at a realistic cadence — each suite's serial path lands in
                // the 5-7 minute range and runs in parallel with the others.
                Thread.sleep(testCase.durationMs);
                if (testCase.shouldPass) {
                    session.record(
                        testCase.name,
                        TestStatus.PASSED,
                        testCase.durationMs,
                        RecordOptions.builder()
                            .suiteName(suiteName)
                            .className("ParallelSuitesWithOneApiKeyTest")
                            .tags(Arrays.asList("parallel", "regression"))
                            .metadata(metadata("worker", suiteName, "expected_result", "business rule holds"))
                            .build()
                    );
                } else {
                    session.record(
                        testCase.name,
                        TestStatus.FAILED,
                        testCase.durationMs,
                        RecordOptions.builder()
                            .suiteName(suiteName)
                            .className("ParallelSuitesWithOneApiKeyTest")
                            .error("AssertionError: expected retry_count <= 2, got 4")
                            .stackTrace("checkout.retry_policy: expected retry_count <= 2, got 4")
                            .tags(Arrays.asList("parallel", "regression", "known-risk"))
                            .metadata(metadata(
                                "worker", suiteName,
                                "assertion", "retry_count <= 2",
                                "expected_result", "gateway timeout is retried at most twice",
                                "actual_result", "gateway timeout retried four times"
                            ))
                            .build()
                    );
                }
            }
        } finally {
            session.close();
        }
        return session.getSentCount();
    }

    private static Map<String, Object> metadata(String... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values;
    }

    private static final class SimulatedCase {
        final String name;
        final boolean shouldPass;
        final long durationMs;

        SimulatedCase(String name, boolean shouldPass, long durationMs) {
            this.name = name;
            this.shouldPass = shouldPass;
            this.durationMs = durationMs;
        }
    }
}
