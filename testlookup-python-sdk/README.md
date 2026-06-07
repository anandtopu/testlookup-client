# testlookup-python-sdk

The Python TestLookup client, packaged for the example projects in this repo —
the Python counterpart of `testlookup-java-sdk/`.

## Source of truth

`testlookup_reporter.py` here is a **vendored copy**. The canonical file lives at:

```
C:\Users\anand\Downloads\Clients\testlookup_reporter.py
```

When the canonical client changes, re-sync the copy and reinstall, exactly as
the Java SDK must be `mvn install`-ed after a change:

```powershell
Copy-Item C:\Users\anand\Downloads\Clients\testlookup_reporter.py `
          .\testlookup-python-sdk\testlookup_reporter.py -Force
pip install -e .\testlookup-python-sdk
```

`run-python-tests.ps1` does both steps for you (it re-syncs then `pip install -e`)
unless you pass `-SkipInstall`.

## What you get

- `import testlookup_reporter` → `TestLookupReporter`, `LiveSession`, `LiveStream`,
  `SyncLiveSession`, `ConfigLoader`.
- A pytest plugin auto-registered via the `pytest11` entry point (see
  `pyproject.toml`). This is the zero-code path used by `pytest_smoke/`; it mirrors
  the `io.testlookup.testng.TestLookupListener` `<listener>` in the Java smoke suite.

## API ↔ Java mapping

| Java SDK                                   | Python SDK                                   |
|--------------------------------------------|----------------------------------------------|
| `TestLookupReporter.Builder().build()`     | `TestLookupReporter(...)`                    |
| `reporter.startSession(SessionOptions...)` | `async with reporter.session(...) as s:`     |
| `session.record(name, status, ms, opts)`   | `await s.record(name, status, ms, **opts)`   |
| `session.close()`                          | context-manager exit (auto)                  |
| `session.getSentCount()`                   | `s.stats["sent"]`                            |
| TestNG `<listener>` (zero-code)            | `pytest11` entry-point plugin (zero-code)    |
| API-key streaming (no session ceremony)    | `LiveStream(api_key=..., run_id=...)`        |
