"""
Sequential slice — ~30% of the generated cases. The xdist_group marker pins all
of these to a single worker under `--dist loadgroup`, so they run one-at-a-time
even while the parallel slice spreads across the other workers. Mirror of the
sequential-execution <test> block (no parallelism) in testng.xml.
"""
import pytest

import case_plan
from allure_steps import execute

_CASES = case_plan.sequential_cases()


@pytest.mark.xdist_group("sequential")
@pytest.mark.parametrize("case", _CASES, ids=[c.name for c in _CASES])
def test_sequential_regression(case):
    execute(case, "sequential")
