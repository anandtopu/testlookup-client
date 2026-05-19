Option A — flip the attribute and run just one (fast, recommended for first-look):

# Edit testng.xml line 26: enabled="false" → enabled="true", then:
.\run-tests.ps1 -SkipInstall -Project realistic -Test Volume100With10FailuresTest
# ~20 seconds, you'll see the new VolumeSummary header / progress / final summary block.

Option B — enable the block and run everything (slow, full picture):

# Same one-character flip in testng.xml, then:
.\run-tests.ps1 -SkipInstall -Project realistic
# ~11 minutes total — runs ParallelSuites + SingleSuite100 (~8 min) plus all three volume suites (~3.5 min).
