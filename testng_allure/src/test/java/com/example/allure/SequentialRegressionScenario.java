package com.example.allure;

import org.testng.annotations.Factory;

/**
 * Sequential slice. Same scenario logic as the parallel one, but its test block
 * in testng.xml declares no parallelism, so these instances run one at a time.
 */
public class SequentialRegressionScenario extends AbstractRegressionScenario {

    @Factory(dataProvider = "sequentialCases", dataProviderClass = CasePlan.class)
    public SequentialRegressionScenario(RegressionCase testCase) {
        super(testCase, "sequential");
    }
}
