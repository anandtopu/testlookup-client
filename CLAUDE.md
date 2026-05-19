# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Workspace layout

Three sibling Maven projects (no parent POM — each builds independently):

- `testlookup-java-sdk/java/` — the SDK itself (`io.testlookup:testlookup-reporter:1.0.0`). Publishes a normal jar **and** a shaded `-all` jar with Jackson relocated to `ai.testlookup.shaded.jackson` (see `pom.xml` shade-plugin config). The two example projects depend on the published `1.0.0` coordinates, so changes to the SDK must be `mvn install`-ed before the examples will pick them up.
- `testng_smoke/` — zero-code TestNG integration example. Tests have no SDK imports; everything is wired via the `<listener>` in `src/test/resources/testng.xml` plus `src/test/resources/testlookup.properties`.
- `testng_realistic/` — programmatic-API examples (`SingleSuiteHundredCasesTest`, `ParallelSuitesWithOneApiKeyTest`) that build a `TestLookupReporter` directly and attach rich step metadata, tags, and run multiple sessions in parallel under one API key.

All three projects target Java 11.

## Common commands

The canonical entry point is `run-tests.ps1` at the repo root. It installs the SDK into the local Maven repo, version-aligns the example POMs (via `versions-maven-plugin:use-dep-version`), then runs `mvn test` in each requested example. Use it instead of stringing the raw `mvn` calls together — it's the only path that auto-syncs after an SDK version bump.

```powershell
# Install SDK, run both example suites
.\run-tests.ps1

# Skip the SDK rebuild, target one project, run a single test method
.\run-tests.ps1 -SkipInstall -Project smoke -Test SmokeTests#checkoutPasses

# Override config via sysprops without editing testlookup.properties
.\run-tests.ps1 -SkipInstall -Project realistic `
    -MvnArgs '-Dtestlookup.endpoint=http://localhost:8000',
             '-Dtestlookup.api.key=qai_real'
```

Raw `mvn` invocations, when you need them:

```powershell
# Build + install the SDK (required before examples can resolve it)
cd testlookup-java-sdk\java; mvn -DskipTests install

# Run an example project's tests — Surefire is wired to src/test/resources/testng.xml
# via <suiteXmlFiles> in each example's pom.xml, not by convention.
cd testng_smoke; mvn test
cd testng_realistic; mvn test

# Single TestNG class
mvn -Dtest=SmokeTests test

# CI metadata overrides (JVM sysprops beat env vars beat the properties file)
mvn test "-Dtestlookup.build=$env:BUILD_NUMBER" "-Dtestlookup.branch=$env:GIT_BRANCH" "-Dtestlookup.commit=$env:GIT_COMMIT"

# Shaded fat jar — produces target/testlookup-reporter-1.0.0.jar AND -1.0.0-all.jar
cd testlookup-java-sdk\java; mvn -DskipTests package
```

## SDK architecture

The reporter is a thin façade over a single REST session and a background batching pipeline. Three classes in `io.testlookup` carry the design:

- **`TestLookupReporter`** is the configured client. `Builder.build()` resolves missing fields by calling `ConfigLoader.load()`, so explicit Builder values override config-file/env/sysprop values, but missing Builder fields fall through to the discovered config. It enforces that `baseUrl + (apiKey | token) + projectId` are present and throws `IllegalStateException` otherwise. Auth is applied per request: `X-API-Key` if `apiKey` is set, else `Authorization: Bearer <token>`.
- **`LiveSession` (nested)** is returned by `reporter.startSession(...)`. Construction immediately POSTs `/api/v1/stream/sessions` and stores the returned `session_id` / `session_token` / `run_id`. Events are added to a bounded `LinkedBlockingQueue<ObjectNode>` (cap 50_000). A daemon `ScheduledExecutorService` flushes every `BATCH_INTERVAL_MS` (100ms default) and an opportunistic flush is triggered when the queue reaches `BATCH_SIZE` (50 default). `postBatch` retries up to `MAX_RETRIES` (5) with exponential back-off (`RETRY_BASE_DELAY_MS << attempt`), but a 401 throws `TestLookupAuthException` which short-circuits the retry loop and marks events as dropped. `close()` shuts the scheduler, drains the queue, then `DELETE`s the session (which also triggers the server-side AI analysis pipeline). `LiveSession` implements `AutoCloseable` — try-with-resources is the expected pattern.
- **`ConfigLoader`** discovers configuration with this precedence (highest wins): Builder args → JVM sysprops → env vars → filesystem `testlookup.properties`/`testlookup.yaml` (cwd, then `.testlookup/`, then `~/.testlookup/`) → classpath `/testlookup.properties`. Within each directory, `.properties` is preferred over `.yaml`. The classpath search is `.properties`-only — YAML on the classpath is **not** picked up. The full set of supported keys (and their canonical config dot-paths) is enumerated in three tables in `ConfigLoader.java`: `PROPERTIES_MAP`, `ENV_MAP`, `SYSPROP_MAP`. Treat those tables as authoritative — they include not just the obvious URL/auth/project keys but also `testlookup.suite`, `testlookup.release`, `testlookup.framework`, and aliases like `testlookup.api_key`/`testlookup.apiKey` for the API-key knob. `isConfigured()` is the predicate the integrations use to decide whether to silently no-op.

#### Suite-name resolution (three layers)

Suite and release names flow through three layers, each beating the layer below. Both examples lean on this, so it's worth holding in your head before changing anything in this area:

1. Per-record — `RecordOptions.suiteName` set on an individual `session.record(...)` call.
2. Per-session — `SessionOptions.suiteName` passed to `reporter.startSession(...)`.
3. Reporter default — `Builder.suiteName(...)` or `ConfigLoader`. The reporter default itself resolves `testlookup.suite` first and falls back to `testlookup.launch` if that's unset (`TestLookupReporter.java:164-167`). The TestNG listener replicates the same `suite > launch` fallback (`TestLookupListener.java:108-111`).

`releaseName` follows the same per-session-beats-reporter-default precedence; a null release name leaves the server free to fall back to the project's default release.

### Framework integrations

Both integrations are designed to **silently no-op** when no TestLookup configuration is present, so they're safe to leave registered in environments that don't have credentials.

- `io.testlookup.testng.TestLookupListener` (implements `ISuiteListener` + `ITestListener`) — reads config in this order per call: suite parameter → `-Dtestlookup.*` → `TESTLOOKUP_*` env var → default. One session per suite (`onStart(ISuite)` → `onFinish(ISuite)`). Records each test in `onTestSuccess/Failure/Skipped` with full stack trace.
- `io.testlookup.junit5.TestLookupExtension` (implements `BeforeAllCallback` + `AfterAllCallback` + `TestWatcher`) — one session per top-level test class, stored in JUnit's `ExtensionContext.Store` under namespace `ai.testlookup`. `getSession()` walks up the parent chain so nested classes share the session. Note: `TestWatcher` doesn't carry a duration, so the extension records `0L` for `durationMs`.

Both `junit-jupiter-api` and `testng` deps in the SDK POM are `provided`+`optional`, so the SDK jar can be on the classpath of either ecosystem without dragging the other in.

## Conventions

- **Don't add real credentials to `src/test/resources/testlookup.properties`.** Both example projects ship placeholder files (`testlookup.api.key=qai_REPLACE_ME`); credentials are expected to be substituted locally or overridden via JVM/env.
- **Bumping the SDK version**: the example POMs pin `io.testlookup:testlookup-reporter:1.0.0` explicitly (not as a property). `run-tests.ps1` auto-syncs them after install via `versions-maven-plugin:use-dep-version` (see `Sync-ExampleSdkVersion`), so the script is the easy path. If you build with raw `mvn install`, you'll need to bump both example POMs by hand or `mvn test` will keep resolving the stale jar.
- **Don't re-enable `createDependencyReducedPom` on the shade plugin.** Only the `-all` classifier is shaded; the main jar still references `com.fasterxml.jackson.*`. If the reduced POM is generated, `mvn install` uses it as the canonical POM for the main artifact, strips Jackson from the dependency list, and consumers crash at runtime with `NoClassDefFoundError: com/fasterxml/jackson/core/JsonFactory`. If you find a stray `testlookup-java-sdk/java/dependency-reduced-pom.xml`, delete it.
- The `ConfigLoader` properties-key map (`PROPERTIES_MAP` in `ConfigLoader.java`) is the canonical list of supported keys. Adding a new config knob requires entries in `PROPERTIES_MAP`, `ENV_MAP`, **and** `SYSPROP_MAP` to be reachable from all three sources.
- **Don't re-silence server errors on session close.** `TestLookupReporter.closeSession` deliberately reads the response body and logs non-2xx status (`TestLookupReporter.java:251-266`); `postJson` deliberately includes the underlying exception class+message (`TestLookupReporter.java:340-342`); and `TestLookupListener.onStart`'s catch block deliberately echoes the exception class so transient connection failures are distinguishable from config errors (`TestLookupListener.java:126-138`). All three were added together on 2026-05-15 after a regression where a 401 on session close left the live-session row stuck in `active` forever and the user had no way to self-diagnose without a debugger. Don't collapse these back into terse one-liners.