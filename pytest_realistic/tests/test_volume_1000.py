"""
Volume replication: 1000 cases — 149 failures, 10 skipped, 841 passed.

Failure and skip indices are precomputed deterministically so re-runs produce
the same pass/fail/skip distribution (useful for diffing the server-side AI
analysis between runs). Per-case sleep is capped at MAX_SLEEP_MS so the full
run lands in ~100s wall-clock at scale 1.0; the reported duration_ms stays
realistic (200ms-23s) so the dashboard's latency view still looks like a full
CI suite.

Mirror of testng_realistic/.../Volume1000WithFailuresAndSkipsTest.java.
"""
import asyncio
import time
import uuid

from testlookup_reporter import TestLookupReporter

import volume_summary
from volume_summary import sleep_ms

TOTAL_CASES = 1000
EXPECTED_FAILURES = 149
EXPECTED_SKIPS = 10
MAX_SLEEP_MS = 100
PROGRESS_EVERY = 100
SUITE_LABEL = "Volume1000WithFailuresAndSkipsTest"

_DOMAINS = ["auth", "checkout", "search", "catalog", "orders",
            "payments", "shipping", "users", "promotions", "inventory"]
_ACTIONS = ["create", "update", "delete", "list", "validate",
            "lookup", "audit", "reconcile", "sync", "expire"]
_CONTEXTS = ["happy_path", "edge_case", "error_path", "concurrent",
             "high_load", "boundary", "permission_denied", "stale_data",
             "race_condition", "idempotency"]


def _compute_indices():
    # Skipped: every 100th case — 100, 200, ..., 1000 → exactly 10.
    skipped = {j for j in range(100, TOTAL_CASES + 1, 100)}

    # Failed: 149 indices evenly spread across the 990 non-skipped cases.
    non_skipped = [i for i in range(1, TOTAL_CASES + 1) if i not in skipped]
    step = len(non_skipped) / EXPECTED_FAILURES
    failed = {non_skipped[int(k * step)] for k in range(EXPECTED_FAILURES)}

    # Belt-and-braces: the set sizes are part of the contract.
    assert len(failed) == EXPECTED_FAILURES, \
        f"failed indices size={len(failed)} expected={EXPECTED_FAILURES}"
    assert len(skipped) == EXPECTED_SKIPS, \
        f"skipped indices size={len(skipped)} expected={EXPECTED_SKIPS}"
    return skipped, failed


SKIPPED_INDICES, FAILED_INDICES = _compute_indices()


def _case_name(i: int) -> str:
    # 10 domains x 10 actions x 10 contexts = 1000 unique combos.
    idx = i - 1
    d = idx % len(_DOMAINS)
    a = (idx // len(_DOMAINS)) % len(_ACTIONS)
    c = (idx // (len(_DOMAINS) * len(_ACTIONS))) % len(_CONTEXTS)
    return f"{_DOMAINS[d]}_{_ACTIONS[a]}_{_CONTEXTS[c]}_{i:04d}"


def _realistic_duration_ms(i: int) -> int:
    bucket = i % 50
    if bucket < 35:
        return 200 + (i * 53) % 600     # 200-800ms (~70%)
    if bucket < 47:
        return 1200 + (i * 71) % 1800   # 1.2-3s    (~24%)
    if bucket < 49:
        return 5000 + (i * 97) % 5000   # 5-10s     (~4%)
    return 15000 + (i * 31) % 8000      # 15-23s    (~2%)


def _metadata_for(i: int, is_failed: bool, is_skipped: bool) -> dict:
    md = {"case_id": f"VOL-1000-{i:04d}"}
    if is_skipped:
        md["skip_reason"] = "blocked by feature flag CHECKOUT_V2 disabled in this env"
    elif is_failed:
        md["expected_result"] = "downstream state is consistent after the call"
        md["actual_result"] = "expected_count != actual_count after operation"
        md["failed_step"] = "then_state_is_consistent"
    else:
        md["expected_result"] = "operation completes and downstream state validates"
    return md


async def _run():
    reporter = TestLookupReporter(framework="pytest-programmatic")
    build_id = f"volume-1000-mixed-{uuid.uuid4().hex[:8]}"
    volume_summary.log_header(SUITE_LABEL, build_id, TOTAL_CASES,
                              EXPECTED_FAILURES, EXPECTED_SKIPS)
    start = time.monotonic()

    passed = failed = skipped = 0
    try:
        async with reporter.session(
            build_number=build_id,
            launch_name="Volume - 1000 cases / 149 failed / 10 skipped",
            release_name=f"volume-{build_id}",
            total_tests=TOTAL_CASES,
        ) as session:
            for i in range(1, TOTAL_CASES + 1):
                duration_ms = _realistic_duration_ms(i)
                is_skipped = i in SKIPPED_INDICES
                is_failed = i in FAILED_INDICES

                # Skipped tests shouldn't burn wall-clock; they never "run".
                if not is_skipped:
                    sleep_ms(min(duration_ms, MAX_SLEEP_MS))

                name = _case_name(i)
                base = dict(
                    suite_name="volume-1000-mixed",
                    class_name="VolumeMixed1000",
                    tags=["volume", "mixed", "size-1000"],
                    metadata=_metadata_for(i, is_failed, is_skipped),
                )
                if is_skipped:
                    await session.record(name, "SKIPPED", 0, **base)
                    skipped += 1
                elif is_failed:
                    await session.record(
                        name, "FAILED", duration_ms,
                        error="AssertionError: expected_count <= actual_count, got mismatch",
                        stack_trace=f"{name}: invariant violated in then_state_is_consistent",
                        **base,
                    )
                    failed += 1
                else:
                    await session.record(name, "PASSED", duration_ms, **base)
                    passed += 1

                if i % PROGRESS_EVERY == 0:
                    volume_summary.log_progress(SUITE_LABEL, i, TOTAL_CASES,
                                                passed, failed, skipped)
        wall_ms = (time.monotonic() - start) * 1000
        volume_summary.log_summary(SUITE_LABEL, build_id, session,
                                   passed, failed, skipped, wall_ms)
        return passed, failed, skipped, session.stats["sent"]
    finally:
        await reporter.aclose()


def test_run_1000_cases_with_149_failures_and_10_skipped():
    passed, failed, skipped, sent = asyncio.run(_run())
    assert passed == TOTAL_CASES - EXPECTED_FAILURES - EXPECTED_SKIPS, "passed count mismatch"
    assert failed == EXPECTED_FAILURES, "failure count mismatch"
    assert skipped == EXPECTED_SKIPS, "skipped count mismatch"
    assert sent >= TOTAL_CASES, f"all {TOTAL_CASES} events should be accepted (got {sent})"
