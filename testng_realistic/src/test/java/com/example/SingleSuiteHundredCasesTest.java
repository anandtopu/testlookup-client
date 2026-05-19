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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class SingleSuiteHundredCasesTest {

    @Test
    public void submitOneSuiteWithHundredCasesAndStepDetails() throws Exception {
        TestLookupReporter reporter = new TestLookupReporter.Builder()
            .framework("testng-programmatic")
            .build();
        List<CheckoutCase> cases = buildCases();
        String buildId = "testng-checkout-100-" + UUID.randomUUID().toString().substring(0, 8);

        LiveSession session = reporter.startSession(
            SessionOptions.builder()
                .buildNumber(buildId)
                .launchName("Checkout suite - 100 cases")
                .releaseName("checkout-" + buildId)
                .totalTests(cases.size())
                .build()
        );
        try {
            for (CheckoutCase testCase : cases) {
                // Sleep for the case's reported duration so events stream into the
                // live UI at a realistic cadence — total wall clock for 100 cases
                // lands in the 5-10 minute window the demo is designed around.
                Thread.sleep(testCase.durationMs);

                RecordOptions.Builder options = RecordOptions.builder()
                    .suiteName("checkout-regression-100")
                    .className("CheckoutRegression")
                    .tags(Arrays.asList("checkout", "regression", "rich-steps"))
                    .metadata(metadataFor(testCase));

                if (testCase.shouldPass) {
                    session.record(testCase.name, TestStatus.PASSED, testCase.durationMs, options.build());
                } else {
                    options.error("AssertionError: order.status expected CONFIRMED but was PAYMENT_REVIEW")
                        .stackTrace(testCase.id + ": then_order_is_confirmed failed");
                    Map<String, Object> enriched = metadataFor(testCase);
                    enriched.put("actual_result", "Order remained in PAYMENT_REVIEW after payment authorization");
                    enriched.put("failed_step", "then_order_is_confirmed");
                    options.metadata(enriched);
                    session.record(testCase.name, TestStatus.FAILED, testCase.durationMs, options.build());
                }
            }
        } finally {
            session.close();
        }

        Assert.assertTrue(session.getSentCount() >= 100, "all 100 cases should be accepted");
    }

    private static List<CheckoutCase> buildCases() {
        List<CheckoutCase> cases = new ArrayList<>();
        String[] paths = {"guest", "registered", "loyalty", "mobile"};
        String[] methods = {"card", "paypal", "gift_card", "bank_transfer"};
        for (int i = 1; i <= 100; i++) {
            String path = paths[i % paths.length];
            String method = methods[i % methods.length];
            boolean shouldPass = i != 17 && i != 42 && i != 73 && i != 88 && i != 95;
            cases.add(new CheckoutCase(
                String.format("CHK-%03d", i),
                String.format("checkout_%s_%s_%03d", path, method, i),
                shouldPass,
                realisticDurationMs(i),
                Arrays.asList(
                    new Step(
                        "given_cart_has_items",
                        "Create a " + path + " cart with two in-stock SKUs",
                        "cart.total > 0",
                        "Cart total is calculated with item subtotal, tax, and shipping"
                    ),
                    new Step(
                        "when_customer_submits_payment",
                        "Submit checkout with " + method,
                        "payment.authorization_status == 'approved'",
                        "Payment provider returns an approved authorization"
                    ),
                    new Step(
                        "then_order_is_confirmed",
                        "Poll order service for the created order",
                        "order.status == 'CONFIRMED'",
                        "Order is confirmed and confirmation email is queued"
                    )
                )
            ));
        }
        return cases;
    }

    /**
     * Mimics a real regression suite: most cases sub-second, a band of mid-tier
     * integration tests (DB / external mocks), a few slow end-to-end paths, and
     * the occasional long async flow. Sums to ~3.5-4 minutes across 100 cases —
     * leaves headroom for the parallel-suites test to also fit inside a 5-10
     * minute combined run.
     */
    private static long realisticDurationMs(int i) {
        int bucket = i % 50;
        if (bucket < 30) return 300 + (i * 53L) % 700;      // 300-1000ms (~60%)
        if (bucket < 45) return 1500 + (i * 71L) % 2000;    // 1.5-3.5s   (~30%)
        if (bucket < 49) return 6000 + (i * 97L) % 6000;    // 6-12s      (~8%)
        return 20000 + (i * 31L) % 10000;                   // 20-30s     (~2%)
    }

    private static Map<String, Object> metadataFor(CheckoutCase testCase) {
        List<Map<String, Object>> stepDefinitions = new ArrayList<>();
        List<String> assertions = new ArrayList<>();
        List<String> expectedResults = new ArrayList<>();
        for (Step step : testCase.steps) {
            Map<String, Object> stepMap = new LinkedHashMap<>();
            stepMap.put("name", step.name);
            stepMap.put("action", step.action);
            stepMap.put("assertion", step.assertion);
            stepMap.put("expected_result", step.expectedResult);
            stepDefinitions.add(stepMap);
            assertions.add(step.assertion);
            expectedResults.add(step.expectedResult);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("case_id", testCase.id);
        metadata.put("step_definitions", stepDefinitions);
        metadata.put("assertions", assertions);
        metadata.put("expected_results", expectedResults);
        return metadata;
    }

    private static final class CheckoutCase {
        final String id;
        final String name;
        final boolean shouldPass;
        final long durationMs;
        final List<Step> steps;

        CheckoutCase(String id, String name, boolean shouldPass, long durationMs, List<Step> steps) {
            this.id = id;
            this.name = name;
            this.shouldPass = shouldPass;
            this.durationMs = durationMs;
            this.steps = steps;
        }
    }

    private static final class Step {
        final String name;
        final String action;
        final String assertion;
        final String expectedResult;

        Step(String name, String action, String assertion, String expectedResult) {
            this.name = name;
            this.action = action;
            this.assertion = assertion;
            this.expectedResult = expectedResult;
        }
    }
}
