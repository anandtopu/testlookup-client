"""
Deterministically generates the full set of simulated regression cases and
partitions it into a parallel slice and a sequential slice.

Mirror of CasePlan.java. Everything is tunable via environment variables so the
suite can be smoke-run in a minute or executed at full 1500-case scale:

    REGRESSION_SUITE_NAME     (default "API Regression 30m")
    REGRESSION_TOTAL_CASES    (default 1500)
    REGRESSION_FAIL_COUNT     (default 100)
    REGRESSION_SKIP_COUNT     (default 200)
    REGRESSION_TARGET_MINUTES (default 30)
    REGRESSION_THREADS        (default 8 — MUST match the -n workers in the runner)
    REGRESSION_PARALLEL_PCT   (default 70 — share of cases run in the parallel block)

Exact pass/fail/skip counts are guaranteed: a status list with precisely
FAIL_COUNT FAIL and SKIP_COUNT SKIP entries (rest PASS) is built and shuffled
with a fixed seed, so the mix is spread across both slices while the totals stay
exact and reproducible.
"""
import os
import random

from regression_case import RegressionCase

_SEED = 42


def _int_env(key, default):
    try:
        return int(os.environ.get(key, str(default)).strip())
    except (ValueError, AttributeError):
        return default


def _float_env(key, default):
    try:
        return float(os.environ.get(key, str(default)).strip())
    except (ValueError, AttributeError):
        return default


def _clamp(v, lo, hi):
    return max(lo, min(hi, v))


SUITE_NAME = os.environ.get("REGRESSION_SUITE_NAME", "API Regression 30m")
_TOTAL = _int_env("REGRESSION_TOTAL_CASES", 1500)
_FAILS = _int_env("REGRESSION_FAIL_COUNT", 100)
_SKIPS = _int_env("REGRESSION_SKIP_COUNT", 200)
_TARGET_MIN = _float_env("REGRESSION_TARGET_MINUTES", 30)
_THREADS = max(1, _int_env("REGRESSION_THREADS", 8))
_PAR_PCT = _clamp(_int_env("REGRESSION_PARALLEL_PCT", 70), 0, 100)

_DOMAINS = ["auth", "accounts", "billing", "catalog", "cart", "checkout", "orders",
            "payments", "shipping", "inventory", "search", "pricing", "promotions",
            "users", "notifications", "webhooks", "reports", "settings", "audit", "sessions"]
_ACTIONS = ["create", "read", "update", "delete", "list", "search", "validate",
            "export", "import", "reconcile", "sync", "expire", "refund", "approve", "cancel"]
_METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"]
_VARIANTS = ["happy_path", "edge_case", "boundary", "negative", "concurrent",
             "idempotency", "pagination", "auth_required", "rate_limited", "large_payload"]

_parallel = None
_sequential = None


def _with_jitter(base, i):
    """Deterministic +/-30% jitter so reported durations look organic."""
    if base <= 0:
        return 0
    span = max(1, base * 60 // 100)              # 60% of base = +/-30%
    offset = (abs((i * 2654435761) % span)) - span // 2
    return max(1, base + offset)


def _build_case(i, outcome, sleep_ms):
    # Mixed-radix walk over (variant, domain, action) so consecutive cases vary
    # across all dimensions; the leading TC<id> keeps the name unique for any total.
    variant = _VARIANTS[(i - 1) % len(_VARIANTS)]
    domain = _DOMAINS[((i - 1) // len(_VARIANTS)) % len(_DOMAINS)]
    action = _ACTIONS[((i - 1) // (len(_VARIANTS) * len(_DOMAINS))) % len(_ACTIONS)]
    method = _METHODS[(i - 1) % len(_METHODS)]
    case_id = f"TC{i:05d}"
    name = f"{case_id}_{domain}_{action}_{variant}"
    endpoint = f"/api/v1/{domain}/{action}"

    failure_reason = None
    skip_reason = None
    if outcome == "FAIL":
        errs = [500, 502, 409, 422, 400]
        http_status = errs[i % len(errs)]
        failure_reason = f"expected 2xx but service returned HTTP {http_status}"
    elif outcome == "SKIP":
        http_status = 0
        reasons = [
            "upstream dependency unavailable in this environment",
            "feature flag disabled for this release",
            "data fixture not provisioned",
            f"blocked by known defect API-{1000 + i}",
        ]
        skip_reason = reasons[i % len(reasons)]
    else:
        http_status = 200

    return RegressionCase(i, case_id, name, domain, action, variant, method, endpoint,
                          outcome, http_status, failure_reason, skip_reason, sleep_ms)


def _ensure_generated():
    global _parallel, _sequential
    if _parallel is not None:
        return

    # 1. Build an exact-count outcome list, then shuffle deterministically.
    outcomes = (["FAIL"] * _FAILS) + (["SKIP"] * _SKIPS)
    outcomes += ["PASS"] * (_TOTAL - len(outcomes))
    random.Random(_SEED).shuffle(outcomes)

    # 2. Decide the parallel/sequential split and per-case time budget so each
    #    slice's wall-clock sums to its share of the target duration.
    parallel_count = round(_TOTAL * (_PAR_PCT / 100.0))
    sequential_count = _TOTAL - parallel_count

    target_ms = round(_TARGET_MIN * 60_000)
    par_wall_ms = round(target_ms * (_PAR_PCT / 100.0))
    seq_wall_ms = target_ms - par_wall_ms

    per_parallel_ms = (par_wall_ms * _THREADS) // parallel_count if parallel_count > 0 else 0
    per_sequential_ms = seq_wall_ms // sequential_count if sequential_count > 0 else 0

    _parallel = []
    _sequential = []
    for i in range(1, _TOTAL + 1):
        outcome = outcomes[i - 1]
        # Bresenham-style even split: picks exactly parallel_count indices,
        # evenly spaced across 1..TOTAL.
        is_parallel = (i * parallel_count // _TOTAL) != ((i - 1) * parallel_count // _TOTAL)
        base = per_parallel_ms if is_parallel else per_sequential_ms
        sleep_ms = _with_jitter(base, i)
        rc = _build_case(i, outcome, sleep_ms)
        (_parallel if is_parallel else _sequential).append(rc)

    # Hard guarantee: every generated case has a distinct test name.
    seen = set()
    for rc in (*_parallel, *_sequential):
        if rc.name in seen:
            raise RuntimeError(f"duplicate test name: {rc.name}")
        seen.add(rc.name)


def parallel_cases():
    _ensure_generated()
    return _parallel


def sequential_cases():
    _ensure_generated()
    return _sequential
