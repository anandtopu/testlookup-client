package com.example.allure;

/**
 * One simulated regression test case: a unique name, a predetermined outcome,
 * and a per-case time budget that the @Step methods sleep through so the whole
 * suite lands near the target wall-clock duration.
 */
public final class RegressionCase {

    public enum Outcome { PASS, FAIL, SKIP }

    public final int index;
    public final String caseId;
    public final String name;
    public final String domain;
    public final String action;
    public final String variant;
    public final String httpMethod;
    public final String endpoint;
    public final Outcome outcome;
    public final int simulatedHttpStatus;
    public final String failureReason;
    public final String skipReason;
    public final long totalSleepMs;

    public RegressionCase(int index, String caseId, String name, String domain, String action,
                          String variant, String httpMethod, String endpoint, Outcome outcome,
                          int simulatedHttpStatus, String failureReason,
                          String skipReason, long totalSleepMs) {
        this.index = index;
        this.caseId = caseId;
        this.name = name;
        this.domain = domain;
        this.action = action;
        this.variant = variant;
        this.httpMethod = httpMethod;
        this.endpoint = endpoint;
        this.outcome = outcome;
        this.simulatedHttpStatus = simulatedHttpStatus;
        this.failureReason = failureReason;
        this.skipReason = skipReason;
        this.totalSleepMs = totalSleepMs;
    }

    /** Setup/fixture phase consumes ~30% of the case budget. */
    public long arrangeMs() { return Math.round(totalSleepMs * 0.30); }

    /** The API call phase consumes ~50%. */
    public long actMs() { return Math.round(totalSleepMs * 0.50); }

    /** Verification phase consumes the remainder. */
    public long assertMs() { return totalSleepMs - arrangeMs() - actMs(); }

    @Override
    public String toString() { return name + " (" + outcome + ")"; }
}
