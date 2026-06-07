"""
Shared logging + timing helpers for the realistic programmatic suites.

Mirror of testng_realistic/src/test/java/com/example/VolumeSummary.java. Output
is plain print() — pytest captures it into the run log (use `-s` to stream it
live) so the simulation shape is visible when scanning a CI log for "what did
this run actually simulate?".
"""
import os
import time

_BORDER = "============================================================"
_DIVIDER = "------------------------------------------------------------"

# Wall-clock scale knob. The Java suites Thread.sleep the full reported duration
# so events stream into the live UI at a realistic cadence (a full run lands in
# the 5-10 minute window the demo is designed around). Set TESTLOOKUP_SLEEP_SCALE
# to shrink that for a fast local run, e.g. 0.05 → ~20x faster, 0 → no sleeping.
_SLEEP_SCALE = float(os.environ.get("TESTLOOKUP_SLEEP_SCALE", "1.0"))


def sleep_ms(ms: float) -> None:
    """Sleep for `ms` milliseconds, scaled by TESTLOOKUP_SLEEP_SCALE."""
    secs = (ms / 1000.0) * _SLEEP_SCALE
    if secs > 0:
        time.sleep(secs)


def log_header(suite_name, build_id, total, planned_failures, planned_skips):
    planned_passed = total - planned_failures - planned_skips
    print()
    print(_BORDER)
    print(f"  {suite_name} — starting simulation")
    print(_DIVIDER)
    print(f"  build_id           {build_id}")
    print(f"  total_cases        {total}")
    print(f"  planned passed     {planned_passed}")
    print(f"  planned failed     {planned_failures}")
    print(f"  planned skipped    {planned_skips}")
    print(_BORDER)


def log_progress(suite_name, done, total, passed, failed, skipped):
    print(f"  [{suite_name}] progress {done}/{total}  "
          f"passed={passed}  failed={failed}  skipped={skipped}")


def log_summary(suite_name, build_id, session, passed, failed, skipped, wall_ms):
    total = passed + failed + skipped
    stats = session.stats
    print()
    print(_BORDER)
    print(f"  {suite_name} — simulation summary")
    print(_DIVIDER)
    print(f"  build_id           {build_id}")
    print(f"  session_id         {session.session_id}")
    print(f"  run_id             {session.run_id}")
    print(_DIVIDER)
    print(f"  total_cases        {total}")
    print(f"    PASSED           {passed}")
    print(f"    FAILED           {failed}")
    print(f"    SKIPPED          {skipped}")
    print(_DIVIDER)
    print(f"  events_sent        {stats.get('sent', 0)}")
    print(f"  events_failed      {stats.get('failed', 0)}")
    print(f"  wall_clock         {_format_duration(wall_ms)}")
    print(_BORDER)


def _format_duration(ms: float) -> str:
    ms = int(ms)
    if ms < 1000:
        return f"{ms}ms"
    total_seconds = ms // 1000
    minutes = total_seconds // 60
    seconds = total_seconds % 60
    millis = ms % 1000
    if minutes > 0:
        return f"{minutes}m {seconds}.{millis:03d}s"
    return f"{seconds}.{millis:03d}s"
