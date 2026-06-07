"""
Programmatic API example: submit one suite of 100 checkout cases with rich
per-step metadata, tags, and a handful of intentional failures.

Mirror of testng_realistic/.../SingleSuiteHundredCasesTest.java. Uses the async
TestLookupReporter.session() context manager (the Python analogue of
reporter.startSession(...) + session.close()).
"""
import asyncio
import uuid

from testlookup_reporter import TestLookupReporter

from volume_summary import sleep_ms

# Cases 17, 42, 73, 88, 95 fail; the rest pass — same indices as the Java suite.
_FAILING = {17, 42, 73, 88, 95}
_PATHS = ["guest", "registered", "loyalty", "mobile"]
_METHODS = ["card", "paypal", "gift_card", "bank_transfer"]


def _realistic_duration_ms(i: int) -> int:
    """Most cases sub-second, a mid-tier band, a few slow E2E paths, the odd
    long async flow — sums to ~3.5-4 min across 100 cases at scale 1.0."""
    bucket = i % 50
    if bucket < 30:
        return 300 + (i * 53) % 700      # 300-1000ms (~60%)
    if bucket < 45:
        return 1500 + (i * 71) % 2000    # 1.5-3.5s   (~30%)
    if bucket < 49:
        return 6000 + (i * 97) % 6000    # 6-12s      (~8%)
    return 20000 + (i * 31) % 10000      # 20-30s     (~2%)


def _steps(path: str, method: str):
    return [
        {
            "name": "given_cart_has_items",
            "action": f"Create a {path} cart with two in-stock SKUs",
            "assertion": "cart.total > 0",
            "expected_result": "Cart total is calculated with item subtotal, tax, and shipping",
        },
        {
            "name": "when_customer_submits_payment",
            "action": f"Submit checkout with {method}",
            "assertion": "payment.authorization_status == 'approved'",
            "expected_result": "Payment provider returns an approved authorization",
        },
        {
            "name": "then_order_is_confirmed",
            "action": "Poll order service for the created order",
            "assertion": "order.status == 'CONFIRMED'",
            "expected_result": "Order is confirmed and confirmation email is queued",
        },
    ]


def _build_cases():
    cases = []
    for i in range(1, 101):
        path = _PATHS[i % len(_PATHS)]
        method = _METHODS[i % len(_METHODS)]
        cases.append({
            "id": f"CHK-{i:03d}",
            "name": f"checkout_{path}_{method}_{i:03d}",
            "should_pass": i not in _FAILING,
            "duration_ms": _realistic_duration_ms(i),
            "steps": _steps(path, method),
        })
    return cases


def _metadata_for(case) -> dict:
    steps = case["steps"]
    return {
        "case_id": case["id"],
        "step_definitions": steps,
        "assertions": [s["assertion"] for s in steps],
        "expected_results": [s["expected_result"] for s in steps],
    }


async def _run() -> int:
    reporter = TestLookupReporter(framework="pytest-programmatic")
    cases = _build_cases()
    build_id = f"pytest-checkout-100-{uuid.uuid4().hex[:8]}"
    try:
        async with reporter.session(
            build_number=build_id,
            launch_name="Checkout suite - 100 cases",
            release_name=f"checkout-{build_id}",
            total_tests=len(cases),
        ) as session:
            for case in cases:
                # Sleep the reported duration so events stream at a realistic
                # cadence (scaled by TESTLOOKUP_SLEEP_SCALE).
                sleep_ms(case["duration_ms"])

                base = dict(
                    suite_name="checkout-regression-100",
                    class_name="CheckoutRegression",
                    tags=["checkout", "regression", "rich-steps"],
                )
                if case["should_pass"]:
                    await session.record(
                        case["name"], "PASSED", case["duration_ms"],
                        metadata=_metadata_for(case), **base,
                    )
                else:
                    enriched = _metadata_for(case)
                    enriched["actual_result"] = \
                        "Order remained in PAYMENT_REVIEW after payment authorization"
                    enriched["failed_step"] = "then_order_is_confirmed"
                    await session.record(
                        case["name"], "FAILED", case["duration_ms"],
                        error="AssertionError: order.status expected CONFIRMED but was PAYMENT_REVIEW",
                        stack_trace=f"{case['id']}: then_order_is_confirmed failed",
                        metadata=enriched, **base,
                    )
        # Read sent count AFTER the context manager exits: the final batch is
        # flushed on session shutdown, mirroring Java's check after close().
        return session.stats["sent"]
    finally:
        await reporter.aclose()


def test_submit_one_suite_with_hundred_cases_and_step_details():
    sent = asyncio.run(_run())
    assert sent >= 100, f"all 100 cases should be accepted (got {sent})"
