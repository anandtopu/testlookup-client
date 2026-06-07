package com.example.allure;

import org.testng.annotations.Factory;

/**
 * Parallel slice. The {@code @Factory} produces one instance per case; the
 * {@code parallel="instances"} + {@code thread-count} on this test's block in
 * testng.xml runs them concurrently.
 */
public class ParallelRegressionScenario extends AbstractRegressionScenario {

    @Factory(dataProvider = "parallelCases", dataProviderClass = CasePlan.class)
    public ParallelRegressionScenario(RegressionCase testCase) {
        super(testCase, "parallel");
    }
}
