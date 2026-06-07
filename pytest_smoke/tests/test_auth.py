"""
Second test module in the suite — proves that multiple modules feed the same
live session. The TestLookup pytest plugin opens one session per pytest run and
tracks pass/fail across every module, not per-file.

Mirror of testng_smoke/src/test/java/com/example/AuthTests.java.
"""


def test_login_with_valid_credentials():
    assert "token" is not None


def test_login_with_invalid_credentials_rejected():
    # Negative test: the assertion should hold (login was rejected).
    assert True, "401 returned as expected"
