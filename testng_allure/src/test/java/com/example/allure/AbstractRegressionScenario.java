package com.example.allure;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Parameter;
import io.qameta.allure.model.Status;
import org.testng.ITest;
import org.testng.annotations.Test;

/**
 * Shared behaviour for a single simulated case. Concrete subclasses only supply
 * the {@code @Factory} that feeds them cases (one for the parallel slice, one for
 * the sequential slice). Implementing {@link ITest} gives TestNG — and therefore
 * the TestLookup listener — the unique per-case name; the Allure name/labels are
 * set dynamically in {@link #execute()}.
 */
public abstract class AbstractRegressionScenario implements ITest {

    protected final RegressionCase testCase;
    private final String executionMode;

    protected AbstractRegressionScenario(RegressionCase testCase, String executionMode) {
        this.testCase = testCase;
        this.executionMode = executionMode;
    }

    @Override
    public String getTestName() {
        return testCase.name;
    }

    @Test
    public void execute() throws Exception {
        decorateAllure();

        AllureSteps steps = new AllureSteps();
        steps.arrange(testCase);
        steps.callEndpoint(testCase.httpMethod, testCase.endpoint, testCase.actMs());
        steps.verifyResponse(testCase);
    }

    private void decorateAllure() {
        // Unique name + historyId so all 1500 cases stay distinct in the report
        // and in Allure history (they otherwise collapse on the shared method name).
        Allure.getLifecycle().updateTestCase(tr -> {
            tr.setName(testCase.name);
            tr.setHistoryId(testCase.name);
            tr.setFullName(getClass().getName() + "#" + testCase.name);
            tr.getParameters().add(new Parameter()
                .setName("caseId").setValue(testCase.caseId));
            tr.getParameters().add(new Parameter()
                .setName("variant").setValue(testCase.variant));
            tr.getParameters().add(new Parameter()
                .setName("execution").setValue(executionMode));
            tr.getParameters().add(new Parameter()
                .setName("endpoint").setValue(testCase.httpMethod + " " + testCase.endpoint));
            if (testCase.outcome == RegressionCase.Outcome.SKIP) {
                tr.setStatus(Status.SKIPPED);
            }
        });

        Allure.epic(CasePlan.SUITE_NAME);
        Allure.feature(testCase.domain);
        Allure.story(testCase.action);
        Allure.label("execution", executionMode);
        Allure.description("Simulated " + testCase.httpMethod + " " + testCase.endpoint
            + " — planned outcome " + testCase.outcome + ".");
    }
}
