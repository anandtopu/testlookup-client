package com.example.allure;

import io.qameta.allure.Attachment;
import io.qameta.allure.Step;
import org.testng.Assert;
import org.testng.SkipException;

/**
 * The reusable, Allure-instrumented step library. Each public method is an
 * {@code @Step}, so it shows up as a named step (with its parameters) in the
 * Allure report. {@code @Attachment} methods attach their return value to the
 * surrounding step. Both require the aspectjweaver javaagent (wired in pom.xml).
 */
public class AllureSteps {

    @Step("Arrange: provision fixtures and authenticate API client")
    public void arrange(RegressionCase c) throws InterruptedException {
        sleep(c.arrangeMs());
    }

    @Step("Act: call {method} {endpoint}")
    public void callEndpoint(String method, String endpoint, long durationMs)
            throws InterruptedException {
        sleep(durationMs);
    }

    @Step("Assert: validate response status and payload schema")
    public void verifyResponse(RegressionCase c) throws InterruptedException {
        sleep(c.assertMs());
        attachResponseBody(simulatedResponseBody(c));

        switch (c.outcome) {
            case PASS:
                Assert.assertEquals(c.simulatedHttpStatus, 200,
                    "expected HTTP 200 for " + c.name);
                break;
            case FAIL:
                Assert.fail(c.failureReason + " (case " + c.name + ")");
                break;
            case SKIP:
                throw new SkipException("Skipped " + c.name + ": " + c.skipReason);
            default:
                throw new IllegalStateException("unknown outcome: " + c.outcome);
        }
    }

    @Attachment(value = "response-body", type = "application/json")
    public String attachResponseBody(String body) {
        return body;
    }

    private static String simulatedResponseBody(RegressionCase c) {
        int status = c.outcome == RegressionCase.Outcome.SKIP ? 0 : c.simulatedHttpStatus;
        return "{\n"
            + "  \"case\": \"" + c.name + "\",\n"
            + "  \"method\": \"" + c.httpMethod + "\",\n"
            + "  \"endpoint\": \"" + c.endpoint + "\",\n"
            + "  \"http_status\": " + status + ",\n"
            + "  \"outcome\": \"" + c.outcome + "\"\n"
            + "}";
    }

    private static void sleep(long ms) throws InterruptedException {
        if (ms > 0) Thread.sleep(ms);
    }
}
