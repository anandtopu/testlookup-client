"""
Parallel slice — ~70% of the generated cases. Run distributed across workers
with `pytest -n <threads>` (pytest-xdist); the runner does this for you. Mirror
of the parallel-execution <test> block (parallel="instances") in testng.xml.
"""
import pytest

import case_plan
from allure_steps import execute

_CASES = case_plan.parallel_cases()


@pytest.mark.parametrize("case", _CASES, ids=[c.name for c in _CASES])
def test_parallel_regression(case):
    execute(case, "parallel")
