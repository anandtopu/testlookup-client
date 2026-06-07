<#
.SYNOPSIS
    Set up the Python TestLookup client and run pytest across the workspace.

.DESCRIPTION
    The Python counterpart of run-tests.ps1. It:
      1. Re-syncs the vendored client from C:\Users\anand\Downloads\Clients\
         (the canonical source) into testlookup-python-sdk\, then
         `pip install -e` it into a local .venv — the analogue of `mvn install`.
      2. Runs pytest in each requested example project.

    Projects:
      smoke     — zero-code: the testlookup-reporter pytest plugin streams plain
                  pytest tests (no SDK imports). Mirrors testng_smoke.
      realistic — programmatic: TestLookupReporter().session() with rich metadata,
                  tags, parallel suites under one API key. Mirrors testng_realistic.
      allure    — 1500-case regression, parallel (xdist) + sequential, allure-pytest
                  alongside TestLookup streaming. Mirrors testng_allure.

.PARAMETER Project
    Which project(s) to test. Default "all". Allowed: smoke, realistic, allure, all, none.

.PARAMETER SkipInstall
    Skip the client re-sync + pip install (use what's already in .venv).

.PARAMETER Test
    Optional pytest -k expression (e.g. "checkout" or "test_dashboard_loads").

.PARAMETER Threads
    xdist worker count for the allure project (default 8 — also exported as
    REGRESSION_THREADS so CasePlan's sleep budget matches the real concurrency).

.PARAMETER PytestArgs
    Extra args forwarded verbatim to pytest, e.g. point at a real backend and
    speed up the run:
        -PytestArgs '-s' `
        -Env @{ 'TESTLOOKUP_ENDPOINT'='http://localhost:8000';
                'TESTLOOKUP_API_KEY'='qai_realKey';
                'TESTLOOKUP_SLEEP_SCALE'='0.02' }

.PARAMETER Env
    Hashtable of environment variables to set for the test runs (config overrides,
    sleep scaling, regression sizing). Env vars beat the testlookup.properties file.

.EXAMPLE
    .\run-python-tests.ps1
    # install client, run smoke + realistic

.EXAMPLE
    .\run-python-tests.ps1 -SkipInstall -Project smoke -Test checkout

.EXAMPLE
    .\run-python-tests.ps1 -SkipInstall -Project allure -Threads 4 `
        -Env @{ 'REGRESSION_TOTAL_CASES'='60'; 'REGRESSION_FAIL_COUNT'='4';
                'REGRESSION_SKIP_COUNT'='8'; 'REGRESSION_TARGET_MINUTES'='1';
                'TESTLOOKUP_SLEEP_SCALE'='0.05' }
    # fast 60-case allure smoke run
#>
[CmdletBinding()]
param(
    [ValidateSet('smoke', 'realistic', 'allure', 'all', 'none')]
    [string]$Project = 'all',

    [switch]$SkipInstall,

    [string]$Test,

    [int]$Threads = 8,

    [string[]]$PytestArgs = @(),

    [hashtable]$Env = @{}
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$SdkDir = Join-Path $root 'testlookup-python-sdk'
$VenvDir = Join-Path $root '.venv'
$VenvPy = Join-Path $VenvDir 'Scripts\python.exe'
$CanonicalClient = 'C:\Users\anand\Downloads\Clients\testlookup_reporter.py'

# Resolve a base Python to create the venv with.
$basePy = (Get-Command python -ErrorAction SilentlyContinue).Source
if (-not $basePy) { $basePy = (Get-Command py -ErrorAction SilentlyContinue).Source }
if (-not $basePy) { throw "Python is not on PATH. Install Python 3.9+ and try again." }

# Resolve target projects.
$targets = switch ($Project) {
    'smoke'     { @('pytest_smoke') }
    'realistic' { @('pytest_realistic') }
    'allure'    { @('pytest_allure') }
    'all'       { @('pytest_smoke', 'pytest_realistic') }
    'none'      { @() }
}

# 1. Create venv + install the client (and example deps) unless skipped.
if (-not $SkipInstall) {
    if (-not (Test-Path $VenvPy)) {
        Write-Host "==> Creating venv at $VenvDir" -ForegroundColor DarkGray
        & $basePy -m venv $VenvDir
        if ($LASTEXITCODE -ne 0) { throw "venv creation failed" }
    }

    # Re-sync the vendored client from its canonical location (mirrors `mvn install`
    # picking up SDK source changes). Skipped with a warning if the source is absent.
    if (Test-Path $CanonicalClient) {
        Write-Host "==> Syncing client: $CanonicalClient -> $SdkDir" -ForegroundColor DarkGray
        Copy-Item $CanonicalClient (Join-Path $SdkDir 'testlookup_reporter.py') -Force
    } else {
        Write-Host "==> Canonical client not found at $CanonicalClient — using the vendored copy" -ForegroundColor Yellow
    }

    Write-Host "==> Installing testlookup-reporter (editable) + pytest, allure-pytest, pytest-xdist" -ForegroundColor DarkGray
    & $VenvPy -m pip install --quiet --upgrade pip
    & $VenvPy -m pip install --quiet -e $SdkDir pytest allure-pytest pytest-xdist pyyaml
    if ($LASTEXITCODE -ne 0) { throw "pip install failed" }
} else {
    Write-Host "==> Skipping client install (use the version already in .venv)" -ForegroundColor Yellow
    if (-not (Test-Path $VenvPy)) { throw ".venv not found — run once without -SkipInstall first." }
}

# 2. Apply env overrides for the test runs.
foreach ($k in $Env.Keys) {
    Set-Item -Path "Env:$k" -Value $Env[$k]
}
$global:env:REGRESSION_THREADS = "$Threads"

# 3. Run pytest per project.
$failed = @()
foreach ($dir in $targets) {
    $workDir = Join-Path $root $dir
    $pyArgs = @()
    if ($Test) { $pyArgs += @('-k', $Test) }
    if ($dir -eq 'pytest_allure') {
        # Parallel slice spreads across workers; sequential slice (xdist_group)
        # pins to one. --alluredir writes the Allure report data.
        # --basetemp keeps pytest's tmp under the project (the shared
        # %TEMP%\pytest-of-* root can hit WinError 5 under xdist on Windows).
        $pyArgs += @('-n', "$Threads", '--dist', 'loadgroup',
                     '--alluredir=allure-results', '--basetemp=.pytest_tmp')
    }
    $pyArgs += $PytestArgs

    Write-Host ""
    Write-Host "==> pytest $($pyArgs -join ' ')  (in $workDir)" -ForegroundColor Cyan
    Push-Location $workDir
    try {
        & $VenvPy -m pytest @pyArgs
        $code = $LASTEXITCODE
        if ($code -ne 0) { $failed += [pscustomobject]@{ Project = $dir; ExitCode = $code } }
    } finally {
        Pop-Location
    }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "==> pytest reported failures in: $($failed.Project -join ', ')" -ForegroundColor Red
    Write-Host "    (a FAILED assertion in a *_fails_intentionally test is expected in smoke)" -ForegroundColor DarkGray
    exit 1
}
Write-Host "==> All requested pytest runs completed successfully." -ForegroundColor Green
