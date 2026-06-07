"""
TestLookup streaming for the allure regression suite — xdist-safe.

Why a custom plugin instead of the bundled zero-code one? pytest parallelism is
process-based (pytest-xdist), whereas the bundled plugin opens a session in
pytest_sessionstart, which fires in EVERY worker process AND the controller —
that would create N+1 sessions and double-record. Under xdist the controller
replays every worker's report through its own pytest_runtest_logreport, so we
stream from the controller ONLY (guarded by the absence of `workerinput`) and
get exactly one live run for the whole suite — the Python analogue of the single
session the Java TestLookupListener opens per suite.

Streaming uses LiveStream (API-key, auto-session keyed by run_id) — the client's
"designed for many concurrent executions" path, matching this suite's intent.
Silently no-ops when no TestLookup config is present, like the Java listener.
"""
import asyncio
import logging
import os
import uuid

logger = logging.getLogger("testlookup_allure_conftest")

_SUITE_NAME = os.environ.get("REGRESSION_SUITE_NAME", "API Regression 30m")


def _is_controller(config) -> bool:
    """True in a non-xdist run or the xdist controller; False in an xdist worker."""
    return not hasattr(config, "workerinput")


class _TestLookupAllurePlugin:
    def __init__(self):
        self._loop = None
        self._stream = None

    def pytest_sessionstart(self, session):
        try:
            from testlookup_reporter import LiveStream
        except Exception as exc:  # pragma: no cover
            logger.warning("testlookup_reporter not importable — streaming disabled: %s", exc)
            return

        run_id = os.environ.get("TESTLOOKUP_RUN_ID") or f"pytest-allure-{uuid.uuid4().hex[:8]}"
        total = None
        try:
            total = int(os.environ.get("REGRESSION_TOTAL_CASES", "1500"))
        except ValueError:
            pass

        try:
            self._loop = asyncio.new_event_loop()
            # base_url / api_key resolve from ./testlookup.properties via ConfigLoader.
            self._stream = LiveStream(
                run_id=run_id,
                framework="pytest-allure",
                total_tests=total,
            )
            self._loop.run_until_complete(self._stream.__aenter__())
            logger.info("TestLookup LiveStream started: run_id=%s", run_id)
        except Exception as exc:
            # Missing config (ValueError) or transport error → disable, don't
            # break the test run. Mirrors the Java listener's silent skip.
            logger.warning("TestLookup streaming disabled — %s", exc)
            if self._loop is not None:
                self._loop.close()
            self._loop = None
            self._stream = None

    def pytest_runtest_logreport(self, report):
        if self._stream is None or self._loop is None:
            return

        # Record once per test: the 'call' phase for pass/fail, the 'setup'
        # phase for skips (skipped tests have no 'call' phase).
        if report.when == "call":
            status = "FAILED" if report.failed else "PASSED"
        elif report.when == "setup" and report.skipped:
            status = "SKIPPED"
        else:
            return

        error = stack = None
        if report.failed and report.longrepr:
            full = str(report.longrepr)
            lines = full.splitlines()
            error = lines[-1] if lines else "Test failed"
            stack = full

        parts = report.nodeid.split("::")
        class_name = parts[0] if len(parts) > 1 else ""
        test_name = "::".join(parts[1:]) if len(parts) > 1 else report.nodeid
        duration_ms = int(getattr(report, "duration", 0) * 1000)

        try:
            self._loop.run_until_complete(self._stream.record(
                test_name=test_name,
                status=status,
                duration_ms=duration_ms,
                suite_name=_SUITE_NAME,
                class_name=class_name,
                error=error,
                stack_trace=stack,
            ))
        except Exception as exc:  # pragma: no cover
            logger.debug("record() failed (non-fatal): %s", exc)

    def pytest_sessionfinish(self, session, exitstatus):
        if self._stream is None or self._loop is None:
            return
        try:
            self._loop.run_until_complete(self._stream.close())
        except Exception as exc:  # pragma: no cover
            logger.warning("LiveStream close failed: %s", exc)
        finally:
            self._loop.close()
            self._loop = None
            self._stream = None


def pytest_configure(config):
    # Quiet the "unknown marker" warning when pytest-xdist isn't installed
    # (xdist registers xdist_group itself when it is).
    config.addinivalue_line("markers", "xdist_group(name): pin tests to one xdist worker")
    # Register the streaming plugin only on the controller / non-xdist process.
    if _is_controller(config):
        config.pluginmanager.register(_TestLookupAllurePlugin(), "testlookup_allure")
