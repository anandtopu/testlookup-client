"""
Programmatic API example: four suites streamed concurrently under ONE API key /
one reporter. Each suite gets its own live session; they run in parallel via
asyncio.gather (the Python analogue of the Java ExecutorService fan-out).

Mirror of testng_realistic/.../ParallelSuitesWithOneApiKeyTest.java.
"""
import asyncio
import uuid

from testlookup_reporter import TestLookupReporter

from volume_summary import sleep_ms

# (name, should_pass, duration_ms) — identical shape/values to the Java suites.
SUITES = {
    "auth-api": [
        ("login_accepts_valid_credentials", True, 1200),
        ("login_rejects_locked_account", True, 950),
        ("refresh_token_rotates", True, 1800),
        ("mfa_challenge_required_for_admin", True, 4500),
        ("password_reset_email_dispatched", True, 12000),
        ("rate_limiter_blocks_brute_force", True, 30000),
        ("device_fingerprint_recorded", True, 3200),
        ("logout_invalidates_all_sessions", True, 18000),
        ("api_key_scope_enforced", False, 22000),
        ("social_login_links_existing_account", True, 90000),
    ],
    "checkout-api": [
        ("cart_total_includes_tax", True, 1300),
        ("expired_coupon_is_rejected", True, 1750),
        ("inventory_decrement_is_atomic", True, 8500),
        ("currency_conversion_uses_daily_rate", True, 2400),
        ("split_shipment_recalculates_freight", True, 16000),
        ("payment_gateway_timeout_is_retried", False, 35000),
        ("idempotency_key_prevents_double_charge", True, 5500),
        ("fraud_check_runs_for_high_value", True, 25000),
        ("end_to_end_guest_checkout", True, 60000),
        ("end_to_end_subscription_renewal", True, 75000),
    ],
    "orders-ui": [
        ("order_history_loads", True, 2200),
        ("order_detail_shows_tracking", True, 3100),
        ("cancel_button_hidden_after_ship", True, 1900),
        ("invoice_pdf_downloads", True, 8400),
        ("address_edit_validates_postcode", True, 4700),
        ("returns_flow_creates_rma", True, 35000),
        ("filter_by_date_range_paginates", False, 12000),
        ("bulk_export_csv_streams", True, 45000),
        ("dark_mode_persists_across_reload", True, 3300),
        ("cross_browser_screenshot_diff", True, 90000),
    ],
    "notifications": [
        ("email_receipt_sent", True, 2500),
        ("sms_opt_out_is_honored", True, 1800),
        ("webhook_signature_is_verified", True, 1100),
        ("push_token_refresh_is_propagated", True, 8000),
        ("digest_email_aggregates_24h", True, 25000),
        ("retry_queue_drains_after_outage", True, 60000),
        ("dead_letter_alert_fires", False, 22000),
        ("template_localization_renders", True, 6500),
        ("delivery_tracking_async_callback", True, 90000),
    ],
}


async def _run_suite(reporter, build_id, suite_name, cases) -> int:
    async with reporter.session(
        build_number=build_id,
        launch_name=f"Parallel suites - {build_id}",
        release_name=f"parallel-{suite_name}-{build_id}",
        total_tests=len(cases),
    ) as session:
        for name, should_pass, duration_ms in cases:
            # Sleep the full reported duration so each suite's serial path
            # streams at a realistic cadence while running in parallel with
            # the others (scaled by TESTLOOKUP_SLEEP_SCALE).
            sleep_ms(duration_ms)
            if should_pass:
                await session.record(
                    name, "PASSED", duration_ms,
                    suite_name=suite_name,
                    class_name="ParallelSuitesWithOneApiKey",
                    tags=["parallel", "regression"],
                    metadata={"worker": suite_name, "expected_result": "business rule holds"},
                )
            else:
                await session.record(
                    name, "FAILED", duration_ms,
                    suite_name=suite_name,
                    class_name="ParallelSuitesWithOneApiKey",
                    error="AssertionError: expected retry_count <= 2, got 4",
                    stack_trace="checkout.retry_policy: expected retry_count <= 2, got 4",
                    tags=["parallel", "regression", "known-risk"],
                    metadata={
                        "worker": suite_name,
                        "assertion": "retry_count <= 2",
                        "expected_result": "gateway timeout is retried at most twice",
                        "actual_result": "gateway timeout retried four times",
                    },
                )
    # Sent count is final once the session context has exited (final flush).
    return session.stats["sent"]


async def _run() -> tuple[int, int]:
    # One reporter == one API key, shared by every concurrent suite session.
    reporter = TestLookupReporter(framework="pytest-programmatic")
    build_id = f"pytest-parallel-{uuid.uuid4().hex[:8]}"
    try:
        results = await asyncio.gather(*(
            _run_suite(reporter, build_id, suite_name, cases)
            for suite_name, cases in SUITES.items()
        ))
        return sum(results), sum(len(c) for c in SUITES.values())
    finally:
        await reporter.aclose()


def test_run_multiple_suites_in_parallel_with_one_api_key():
    sent, expected = asyncio.run(_run())
    assert sent >= expected, \
        f"all parallel suite events should be accepted (got {sent} of {expected})"
