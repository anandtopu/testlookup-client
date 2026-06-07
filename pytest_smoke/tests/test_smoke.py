"""
Demonstrates that ordinary pytest tests stream to TestLookup with zero
test-code changes. This file has no TestLookup imports — the pytest plugin
(auto-registered by the installed testlookup-reporter package) does all the
wiring, picking up config from ./testlookup.properties.

Mirror of testng_smoke/src/test/java/com/example/SmokeTests.java.
"""
import time

import pytest


def _sleep(ms: int) -> None:
    time.sleep(ms / 1000.0)


def test_checkout_passes():
    _sleep(40)
    assert 1 + 1 == 2


def test_dashboard_loads():
    _sleep(120)
    assert True


def test_payment_fails_intentionally():
    """Demonstrates a FAILED status reaching the live UI."""
    _sleep(80)
    assert 500 == 200, "expected HTTP 200, got 500"


@pytest.mark.skip(reason="Demonstrates a SKIPPED status")
def test_feature_flagged_off():
    pytest.fail("should never run")


@pytest.mark.parametrize("n", [1, 2, 3])
def test_parametrised_row(n):
    assert n > 0
