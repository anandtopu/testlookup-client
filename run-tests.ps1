<#
.SYNOPSIS
    Build the TestLookup SDK and run Maven tests across the workspace.

.DESCRIPTION
    Installs io.testlookup:testlookup-reporter:1.0.0 into the local Maven
    repo, then runs `mvn test` in each example project that depends on it.

.PARAMETER Project
    Which example project(s) to test. Defaults to "all".
    Allowed: smoke, realistic, all, none.

.PARAMETER SkipInstall
    Skip the SDK install step (use the version already in your local repo).

.PARAMETER Test
    Optional Surefire -Dtest=... filter (e.g. SmokeTests, SmokeTests#checkoutPasses).

.PARAMETER MvnArgs
    Extra args forwarded verbatim to `mvn test`. Use this to override sysprops
    without editing testlookup.properties — e.g. point at localhost via a
    kubectl port-forward, inject a real API key, or stamp the run with a
    release name for one execution:
        -MvnArgs '-Dtestlookup.endpoint=http://localhost:8000',
                 '-Dtestlookup.api.key=qai_realKey123',
                 '-Dtestlookup.release=2026.05.13-rc1'
    JVM sysprops beat env vars beat the properties file (see testlookup-client/CLAUDE.md).

.EXAMPLE
    .\run-tests.ps1
    # install SDK, run both example suites

.EXAMPLE
    .\run-tests.ps1 -Project smoke -Test SmokeTests#checkoutPasses
    # install SDK, run a single test method in the smoke project

.EXAMPLE
    .\run-tests.ps1 -SkipInstall -Project realistic
    # skip rebuild, only run the realistic example

.EXAMPLE
    .\run-tests.ps1 -SkipInstall -Project realistic `
        -MvnArgs '-Dtestlookup.endpoint=http://localhost:8000',
                 '-Dtestlookup.api.key=qai_yourRealKey'
    # point the realistic example at a port-forwarded backend with a real key
#>
[CmdletBinding()]
param(
    [ValidateSet('smoke', 'realistic', 'all', 'none')]
    [string]$Project = 'all',

    [switch]$SkipInstall,

    [string]$Test,

    [string[]]$MvnArgs = @()
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$SdkDir = Join-Path $root 'testlookup-java-sdk\java'
$PomNs  = 'http://maven.apache.org/POM/4.0.0'

function Invoke-Mvn {
    # Parameter renamed from -MvnArgs to -Arguments so it doesn't collide with
    # the script-level -MvnArgs that the user passes for sysprop overrides.
    # -AllowFailure: return the exit code instead of throwing. Use for `mvn test`
    # so a failing assertion in one project doesn't skip later projects.
    param([string]$WorkDir, [string[]]$Arguments, [switch]$AllowFailure)

    Write-Host ""
    Write-Host "==> mvn $($Arguments -join ' ')  (in $WorkDir)" -ForegroundColor Cyan

    Push-Location $WorkDir
    try {
        & mvn @Arguments
        $code = $LASTEXITCODE
        if ($code -ne 0 -and -not $AllowFailure) {
            throw "mvn exited with code $code in $WorkDir"
        }
        if ($AllowFailure) { return $code }
    } finally {
        Pop-Location
    }
}

function Get-PomXPath {
    param([string]$PomPath, [string]$XPath)
    [xml]$xml = Get-Content -LiteralPath $PomPath
    $ns = New-Object System.Xml.XmlNamespaceManager($xml.NameTable)
    $ns.AddNamespace('m', $PomNs)
    $node = $xml.SelectSingleNode($XPath, $ns)
    if ($null -eq $node) { return $null }
    return $node.InnerText.Trim()
}

function Get-SdkVersion {
    $v = Get-PomXPath -PomPath (Join-Path $SdkDir 'pom.xml') -XPath '/m:project/m:version'
    if (-not $v) { throw "Could not read SDK version from $SdkDir\pom.xml" }
    return $v
}

function Get-ExampleSdkVersion {
    param([string]$WorkDir)
    return Get-PomXPath -PomPath (Join-Path $WorkDir 'pom.xml') `
        -XPath "/m:project/m:dependencies/m:dependency[m:groupId='io.testlookup' and m:artifactId='testlookup-reporter']/m:version"
}

function Sync-ExampleSdkVersion {
    # Uses versions-maven-plugin (resolved from Central) to rewrite the
    # io.testlookup:testlookup-reporter dep version in-place. No-op when the
    # current version already matches (we check first to avoid POM churn).
    param([string]$WorkDir, [string]$TargetVersion)

    $current = Get-ExampleSdkVersion -WorkDir $WorkDir
    if ($current -eq $TargetVersion) { return }

    Write-Host "==> Aligning $WorkDir SDK dep: $current -> $TargetVersion" -ForegroundColor DarkGray
    Invoke-Mvn -WorkDir $WorkDir -Arguments @(
        '-q',
        'org.codehaus.mojo:versions-maven-plugin:2.16.2:use-dep-version',
        '-Dincludes=io.testlookup:testlookup-reporter',
        "-DdepVersion=$TargetVersion",
        '-DforceVersion=true',
        '-DgenerateBackupPoms=false'
    )
}

# Sanity check: mvn must be on PATH.
if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw "mvn is not on PATH. Install Maven or add it to PATH and try again."
}

# Resolve targets up front so we know which example POMs need version-aligning.
$targets = switch ($Project) {
    'smoke'     { @('testng_smoke') }
    'realistic' { @('testng_realistic') }
    'all'       { @('testng_smoke', 'testng_realistic') }
    'none'      { @() }
}

$sdkVersion = Get-SdkVersion
Write-Host "==> testlookup-reporter version (from SDK pom): $sdkVersion" -ForegroundColor DarkGray

# 1. Clean-rebuild and install the SDK so the example projects resolve a fresh
#    io.testlookup:testlookup-reporter:$sdkVersion from the local Maven repo.
if (-not $SkipInstall) {
    Invoke-Mvn -WorkDir $SdkDir -Arguments @('clean', '-DskipTests', 'install')

    # 1b. If the SDK version was bumped, point each example POM at the new
    #     coordinates so `mvn test` doesn't keep resolving the old jar.
    foreach ($dir in $targets) {
        Sync-ExampleSdkVersion -WorkDir (Join-Path $root $dir) -TargetVersion $sdkVersion
    }
} else {
    Write-Host "==> Skipping SDK rebuild (use -SkipInstall:`$false to rebuild)" -ForegroundColor Yellow
}

# 2. Run example test suites.
$mvnTestArgs = @('test')
if ($Test) {
    $mvnTestArgs += "-Dtest=$Test"
}
# Forward user-supplied -MvnArgs (e.g. -Dtestlookup.endpoint=...) to every test run.
# Skipped for the SDK install step above — those flags only matter at test time.
if ($MvnArgs.Count -gt 0) {
    $mvnTestArgs += $MvnArgs
}

$failed = @()
foreach ($dir in $targets) {
    $code = Invoke-Mvn -WorkDir (Join-Path $root $dir) -Arguments $mvnTestArgs -AllowFailure
    if ($code -ne 0) { $failed += [pscustomobject]@{ Project = $dir; ExitCode = $code } }
}

Write-Host ""
if ($failed.Count -gt 0) {
    Write-Host "==> Test failures in: $($failed.Project -join ', ')" -ForegroundColor Red
    Write-Host "    (other steps completed; see surefire-reports for per-test detail)" -ForegroundColor DarkGray
    exit 1
}
Write-Host "==> All requested Maven steps completed successfully." -ForegroundColor Green
