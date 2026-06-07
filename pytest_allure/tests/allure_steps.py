"""
Allure @step building blocks plus the shared per-case execution body. Mirror of
AllureSteps.java + AbstractRegressionScenario.execute()/decorateAllure().

allure-pytest records each @allure.step into the run when --alluredir is set;
the testlookup conftest plugin streams the same pass/fail/skip independently.
"""
import os
import time

import allure
import pytest

import case_plan
from regression_case import RegressionCase

# Same wall-clock scale knob as the realistic suite. The @Factory sleeps in the
# Java suite size the run to ~30 min; set this to shrink that for a fast run,
# e.g. 0.01, or 0 to disable sleeping entirely.
_SLEEP_SCALE = float(os.environ.get("TESTLOOKUP_SLEEP_SCALE", "1.0"))


@allure.step("Arrange fixtures and preconditions")
def arrange(case: RegressionCase) -> None:
    # No real setup in the simulation — the step exists so the Allure report
    # shows the same arrange/act/assert shape a real API test would.
    pass


@allure.step("Call endpoint")
def call_endpoint(http_method: str, endpoint: str, act_ms: int) -> None:
    with allure.step(f"{http_method} {endpoint}"):
        secs = (act_ms / 1000.0) * _SLEEP_SCALE
        if secs > 0:
            time.sleep(secs)


@allure.step("Verify response")
def verify_response(case: RegressionCase) -> None:
    if case.outcome == "FAIL":
        raise AssertionError(case.failure_reason)


def execute(case: RegressionCase, execution_mode: str) -> None:
    """Shared body for a single simulated case (parallel + sequential slices)."""
    _decorate_allure(case, execution_mode)

    # SKIP is decided up front and never burns the act() sleep — matches how
    # real frameworks short-circuit a skipped test.
    if case.outcome == "SKIP":
        pytest.skip(case.skip_reason)

    arrange(case)
    call_endpoint(case.http_method, case.endpoint, case.act_ms())
    verify_response(case)


def _decorate_allure(case: RegressionCase, execution_mode: str) -> None:
    allure.dynamic.title(case.name)
    allure.dynamic.epic(case_plan.SUITE_NAME)
    allure.dynamic.feature(case.domain)
    allure.dynamic.story(case.action)
    allure.dynamic.label("execution", execution_mode)
    allure.dynamic.parameter("caseId", case.case_id)
    allure.dynamic.parameter("variant", case.variant)
    allure.dynamic.parameter("endpoint", f"{case.http_method} {case.endpoint}")
    allure.dynamic.description(
        f"Simulated {case.http_method} {case.endpoint} — planned outcome {case.outcome}."
    )
