package com.example.allure;

import org.testng.annotations.DataProvider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Deterministically generates the full set of simulated regression cases and
 * partitions it into a parallel slice and a sequential slice.
 *
 * <p>Everything is tunable via system properties so the suite can be smoke-run
 * in a minute or executed at full 1500-case / 30-minute scale:
 * <pre>
 *   regression.suiteName     (default "API Regression 30m")
 *   regression.totalCases    (default 1500)
 *   regression.failCount     (default 100)
 *   regression.skipCount     (default 200)
 *   regression.targetMinutes (default 30)
 *   regression.threads       (default 8 — MUST match thread-count in testng.xml)
 *   regression.parallelPct   (default 70 — share of cases run in the parallel block)
 * </pre>
 *
 * <p>Exact pass/fail/skip counts are guaranteed: a status list with precisely
 * {@code failCount} FAIL and {@code skipCount} SKIP entries (rest PASS) is built
 * and shuffled with a fixed seed, so the mix is spread across both slices while
 * the totals stay exact and reproducible.
 */
public final class CasePlan {

    public static final String SUITE_NAME =
        System.getProperty("regression.suiteName", "API Regression 30m");

    private static final int    TOTAL      = intProp("regression.totalCases", 1500);
    private static final int    FAILS      = intProp("regression.failCount", 100);
    private static final int    SKIPS      = intProp("regression.skipCount", 200);
    private static final double TARGET_MIN = dblProp("regression.targetMinutes", 30);
    private static final int    THREADS    = Math.max(1, intProp("regression.threads", 8));
    private static final int  PAR_PCT  = clamp(intProp("regression.parallelPct", 70), 0, 100);

    private static final long SEED = 42L;

    private static final String[] DOMAINS = {
        "auth", "accounts", "billing", "catalog", "cart", "checkout", "orders",
        "payments", "shipping", "inventory", "search", "pricing", "promotions",
        "users", "notifications", "webhooks", "reports", "settings", "audit", "sessions"
    };
    private static final String[] ACTIONS = {
        "create", "read", "update", "delete", "list", "search", "validate",
        "export", "import", "reconcile", "sync", "expire", "refund", "approve", "cancel"
    };
    private static final String[] METHODS = { "GET", "POST", "PUT", "PATCH", "DELETE" };
    private static final String[] VARIANTS = {
        "happy_path", "edge_case", "boundary", "negative", "concurrent",
        "idempotency", "pagination", "auth_required", "rate_limited", "large_payload"
    };

    private static List<RegressionCase> parallel;
    private static List<RegressionCase> sequential;

    private CasePlan() { }

    public static synchronized void ensureGenerated() {
        if (parallel != null) return;

        // 1. Build an exact-count outcome list, then shuffle deterministically.
        List<RegressionCase.Outcome> outcomes = new ArrayList<>(TOTAL);
        for (int i = 0; i < FAILS; i++) outcomes.add(RegressionCase.Outcome.FAIL);
        for (int i = 0; i < SKIPS; i++) outcomes.add(RegressionCase.Outcome.SKIP);
        while (outcomes.size() < TOTAL) outcomes.add(RegressionCase.Outcome.PASS);
        Collections.shuffle(outcomes, new Random(SEED));

        // 2. Decide the parallel/sequential split and the per-case time budget so
        //    each slice's wall-clock sums to its share of the target duration.
        int parallelCount   = (int) Math.round(TOTAL * (PAR_PCT / 100.0));
        int sequentialCount = TOTAL - parallelCount;

        long targetMs       = Math.round(TARGET_MIN * 60_000d);
        long parWallMs      = Math.round(targetMs * (PAR_PCT / 100.0));
        long seqWallMs      = targetMs - parWallMs;

        long perParallelMs   = parallelCount   > 0 ? (parWallMs * THREADS) / parallelCount : 0;
        long perSequentialMs = sequentialCount > 0 ?  seqWallMs / sequentialCount          : 0;

        parallel   = new ArrayList<>(parallelCount);
        sequential = new ArrayList<>(sequentialCount);

        for (int i = 1; i <= TOTAL; i++) {
            RegressionCase.Outcome outcome = outcomes.get(i - 1);
            // Bresenham-style even split: picks exactly parallelCount indices,
            // evenly spaced across 1..TOTAL, for any total. Combined with the
            // shuffled outcomes, both slices get a representative pass/fail/skip mix.
            boolean isParallel =
                (i * (long) parallelCount / TOTAL) != ((i - 1) * (long) parallelCount / TOTAL);
            long base = isParallel ? perParallelMs : perSequentialMs;
            long sleepMs = withJitter(base, i);

            RegressionCase rc = buildCase(i, outcome, sleepMs);
            if (isParallel) parallel.add(rc);
            else            sequential.add(rc);
        }

        // Hard guarantee: every generated case has a distinct test name.
        java.util.Set<String> seen = new java.util.HashSet<>(TOTAL * 2);
        for (RegressionCase rc : parallel)
            if (!seen.add(rc.name)) throw new IllegalStateException("duplicate test name: " + rc.name);
        for (RegressionCase rc : sequential)
            if (!seen.add(rc.name)) throw new IllegalStateException("duplicate test name: " + rc.name);
    }

    private static RegressionCase buildCase(int i, RegressionCase.Outcome outcome, long sleepMs) {
        // Mixed-radix walk over (variant, domain, action) so consecutive cases
        // vary across all dimensions and the combos stay distinct; the leading
        // TC<id> still makes the name unique for any total (even beyond the combo
        // space) and the CasePlan uniqueness check enforces it.
        String variant = VARIANTS[(i - 1) % VARIANTS.length];
        String domain  = DOMAINS[((i - 1) / VARIANTS.length) % DOMAINS.length];
        String action  = ACTIONS[((i - 1) / (VARIANTS.length * DOMAINS.length)) % ACTIONS.length];
        String method  = METHODS[(i - 1) % METHODS.length];
        String caseId  = String.format("TC%05d", i);
        String name    = String.format("%s_%s_%s_%s", caseId, domain, action, variant);
        String endpoint = "/api/v1/" + domain + "/" + action;

        int httpStatus;
        String failureReason = null;
        String skipReason = null;
        switch (outcome) {
            case FAIL:
                int[] errs = { 500, 502, 409, 422, 400 };
                httpStatus = errs[i % errs.length];
                failureReason = "expected 2xx but service returned HTTP " + httpStatus;
                break;
            case SKIP:
                httpStatus = 0;
                String[] reasons = {
                    "upstream dependency unavailable in this environment",
                    "feature flag disabled for this release",
                    "data fixture not provisioned",
                    "blocked by known defect API-" + (1000 + i)
                };
                skipReason = reasons[i % reasons.length];
                break;
            default:
                httpStatus = 200;
        }
        return new RegressionCase(i, caseId, name, domain, action, variant, method, endpoint,
                                  outcome, httpStatus, failureReason, skipReason, sleepMs);
    }

    /** Deterministic +/-30% jitter so reported durations look organic. */
    private static long withJitter(long base, int i) {
        if (base <= 0) return 0;
        long span = Math.max(1, base * 60 / 100);            // 60% of base = +/-30%
        long offset = (Math.abs((i * 2654435761L) % span)) - span / 2;
        return Math.max(1, base + offset);
    }

    public static synchronized List<RegressionCase> parallelCases() {
        ensureGenerated();
        return parallel;
    }

    public static synchronized List<RegressionCase> sequentialCases() {
        ensureGenerated();
        return sequential;
    }

    @DataProvider(name = "parallelCases", parallel = false)
    public static Object[][] parallelCasesProvider() {
        return toRows(parallelCases());
    }

    @DataProvider(name = "sequentialCases", parallel = false)
    public static Object[][] sequentialCasesProvider() {
        return toRows(sequentialCases());
    }

    private static Object[][] toRows(List<RegressionCase> cases) {
        Object[][] rows = new Object[cases.size()][1];
        for (int i = 0; i < cases.size(); i++) rows[i][0] = cases.get(i);
        return rows;
    }

    private static int intProp(String key, int def) {
        try { return Integer.parseInt(System.getProperty(key, Integer.toString(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static double dblProp(String key, double def) {
        try { return Double.parseDouble(System.getProperty(key, Double.toString(def)).trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
