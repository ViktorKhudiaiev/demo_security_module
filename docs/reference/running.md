# Local service and load-test scripts

Requirements: Windows PowerShell, Java 21, Maven, Docker Desktop with Linux containers, and Node.js 20+ for load/scenario runners. Bundled Java/Maven under `.local/tools/` are detected; otherwise commands are resolved from PATH. Verification also discovers an existing Node runtime outside PATH and validates its version. All script/data paths resolve from the script location; relative explicit runtime overrides resolve from the caller's directory.

Node discovery order: explicit `DEMO_NODE_PATH` override; `node` applications on PATH; `.local/tools/node/node.exe` or `.local/tools/node/bin/node.exe`; standard Windows Node installation directories; the existing current-user runtime at `%USERPROFILE%/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/bin/node.exe`. Nothing is downloaded automatically and PATH is not changed. An invalid explicit override stops the run instead of silently choosing another binary. The chosen absolute path/version is recorded in the report.

The documentation cleanup relocated this workstation's bundled JDK to `.local/tools/jdk-21.0.11+10`. If an existing IntelliJ SDK entry still points to the old `tools/` directory, update that SDK's home directory to the new path. The scripts already use the relocated runtime. A fresh checkout may use any supported Java 21 installation and Maven on PATH; local distributions are not committed or included in the documentation package.

Full verification in one command (build/tests, local startup, adversarial scenarios, 20 TPS for two minutes, then restart with test hooks disabled):

```powershell
.\scripts\verify-local.ps1
# Longer soak and strict measured 20 TPS, with no finite-window tolerance:
.\scripts\verify-local.ps1 -DurationSeconds 600 -RateTolerance 0
```

If local PowerShell script execution is disabled, run this trusted project in a new process without changing the persistent system policy:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\verify-local.ps1" -DurationSeconds 120
```

For another existing Node installation, set `$env:DEMO_NODE_PATH = 'C:\path\to\node.exe'` in the calling shell. Direct `node ...` examples below still require Node on PATH; otherwise invoke the selected binary explicitly, for example `. .\scripts\common.ps1; & (Get-DemoNode) .\scripts\load.mjs --rate 20 --seconds 120`.

The dependency-free discovery regression suite is `scripts/node-runtime-tests.ps1`. It covers missing PATH, explicit overrides, version validation and paths containing spaces, and only uses temporary test fixtures and process-local environment changes.

The combined report is written under `.local/verification-<timestamp>-<id>/report.json` with links/paths to scenario and load evidence. Errors return a nonzero exit code. Verification first runs the load-evidence unit tests and stops only this project's tracked JVMs before Maven clean (Windows locks running JARs). A failed build leaves those JVMs stopped, with databases/keys intact. Normal-mode restoration is attempted after test-mode startup, even after a scenario/load failure, and recorded separately; a restoration problem does not erase the original test failure. No generated report is evidence of success unless its `passed` field is true.

```powershell
# Unit/component tests and executable JAR packaging (all three modules)
.\scripts\stop.ps1 -KeepDatabases  # required before cleaning live JARs on Windows
.\scripts\test.ps1

# Start databases, initialize schemas, and launch three hidden JVM processes
.\scripts\start.ps1 -SkipBuild
.\scripts\status.ps1

# Fixed-rate load: 20 submitted requests/second, requiring completed settlement
# AND durable audit outcomes. Setup/funding is excluded from measurement.
node .\scripts\load.mjs --rate 20 --seconds 120

# Longer local soak; optionally require zero finite-window rate tolerance
node .\scripts\load.mjs --rate 20 --seconds 600 --rate-tolerance 0

# Adversarial scenarios require explicit local test hooks. Stop existing JVMs
# first: startup does not silently replace a running service's configuration.
.\scripts\stop.ps1 -KeepDatabases
.\scripts\start.ps1 -SkipBuild -EnableTestFaults
node .\scripts\scenarios.mjs

# Stop only this demo's recorded/verified JVMs and Compose containers
.\scripts\stop.ps1
```

`run.ps1` is an alias for `start.ps1`. Without `-SkipBuild`, startup stops the owned JVMs (preserving databases) and runs Maven `clean verify`. Maven downloads go to the ignored `.m2-cache/`; `MAVEN_REPO_LOCAL` overrides that path. `test.ps1 -Offline` never fetches dependencies and therefore needs an already populated cache. `DEMO_DOCKER_PATH` overrides Docker CLI discovery.

No script deletes Docker volumes or unrelated application data. A repeated startup reuses existing keys and database volumes. Schema initialization records a SHA-256 checksum and refuses to silently replace a changed schema; such changes need an explicit reviewed migration. Runtime database identities cannot run schema DDL. Application, processor, and key-service roles receive different, limited privileges.

## Live transaction lab

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\live-demo.ps1" -StartStack
```

Keep that terminal open and visit `http://127.0.0.1:8090/docs/live/index.html`. The helper binds only to `127.0.0.1:8090`; Ctrl+C stops the helper without deleting fixtures or stopping the databases. If the stack is already running, omit `-StartStack`. The launcher reuses built JARs and existing JVM settings; it does not silently disable fault hooks left by an interrupted test. Do not run acceptance, fault or recovery tests while presenting.

The page offers protected $1,000 funding of a dedicated Alice account, one authenticated $25 transfer to dedicated Bob, and one fabricated $25 primary row without issuance. Repeat clicks retry persistent identities rather than make additional payments. The forgery must be quarantined with `No independent issuance receipt`, no financial postings and unchanged protected balances. This case stops before HMAC verification. Read-only fixture-scoped queries show Primary, Audit and Settlement tables with observation timestamps; these are not one global database snapshot.

The helper is trusted local teaching infrastructure. Its fixed attacker action writes only its own primary row and outbox hint. It has no user-supplied SQL, IDs, amounts, arbitrary file serving, proxy or process-control endpoint. Exact Host/Origin checks, a per-process browser session capability and text-only rendering constrain the local web interface. Real backend credentials remain server-side. `.local/live-demo` retains fixture ownership and verification results and must not be shared wholesale.

```powershell
node --test docs/live/live-demo.test.mjs
# Explicitly exercise the live page's API against the running stack:
node docs/live/integration.mjs
```

After both experiments pass, **New demo accounts** creates fresh persistent identities without deleting previous records. Click Prepare to fund the new accounts. The helper refuses to advance while experiment evidence is incomplete. Ownership manifests for completed demonstrations remain under `.local/live-demo/history`.

The integration runner uses only the helper's dedicated fixtures and writes a new local report. It does not rerun the 20 TPS benchmark. The portable presentation archive contains the lab page, not the backend or Java services; execution requires this complete checkout.

## Local endpoints and trust boundary

| Service | Local endpoint | Database runtime identity |
| --- | --- | --- |
| Application | `127.0.0.1:8080` | `primary_app` |
| Key service | `127.0.0.1:8081` | `audit_key` |
| Processor | `127.0.0.1:8082` | `primary_processor`, `audit_processor`, `settlement_processor` |
| Primary PostgreSQL | `127.0.0.1:55431` | Separate primary database instance |
| Audit PostgreSQL | `127.0.0.1:55432` | Separate audit database instance |
| Settlement PostgreSQL | `127.0.0.1:55433` | Separate settlement database instance |

Generated passwords and API credentials live in ignored `.local/secrets.json`; Compose sees only database-administrator credentials in `.local/compose.env`. These files must never be committed, sent with a presentation, or pasted into logs. Credentials are random, not sample literals. The `.local/` Windows ACL is restricted to the executing identity and workspace owner. Host Java process environments contain only the credentials needed by each service; raw HMAC and signing keys are never put in those environments.

The host/Windows/Docker administrator can still read process memory, local key files and all volumes. Two local databases are **not** independently administered cloud accounts. This demo models the primary-DB-only attacker; production KMS/HSM, independent IAM, TLS and durable external anchoring remain separate deployment work.

Startup supports `-EnableTestFaults` solely for fault-injection tests. It is off by default. Stop the application and start again without this flag before presenting it. `start.ps1 -SkipBuild` reuses live JVMs without changing their settings; startup with a build explicitly stops and replaces them.

## Reading the load result

Each run creates fresh test accounts and simulated protected funding. It does not modify or delete unrelated accounts. Evidence is stored in `.local/load-<timestamp>-<id>/`:

- `report.json`: configuration, acceptance booleans, throughput, durable completion latency, balance reconciliation, errors and limitations.
- `operations.json`: scheduled, submitted, accepted and durably completed timestamps per operation.
- `samples.json`: submitted/completed/in-flight counts every second.

HTTP `202` alone is not success. Every distinct request must have a distinct operation UUID, `COMPLETED`, `outcomeRelayed=true`, matching protected payload and exactly the requested debit/credit legs; final protected balances must also match. This prevents replayed circular transfers from passing merely because net balances cancel. `load-evidence.test.mjs` exercises this validator against replayed IDs, wrong payloads and wrong postings. Failed or missing operations produce a nonzero exit code.

The report distinguishes offered TPS, steady-state **completed** TPS, and whole-run completed TPS including final draining. Default steady-state rate tolerance is explicitly 2% for finite-window scheduling; it is not a claim that 19.6 equals 20. Use `--rate-tolerance 0` for a strict measured threshold. Warmup is 10 seconds (short runs use at most one quarter of their duration). A growing in-flight queue is checked independently; this short-run check is not evidence of unlimited sustained capacity. The test is a local demonstration, not a production sizing or financial-network benchmark.

## Adversarial scenarios

`scenarios.mjs` verifies credential separation, protected funding, ordinary and concurrent idempotency, conflicting retries, runtime DB write restrictions, forged rows, missing issued records, post-verification tampering/deletion, rollback after the debit leg, outbox replay, and tampering with a completed source transaction. It waits for durable audit outcomes and checks protected balances/posting counts.

The harness uses the installed Docker CLI directly, sends SQL through stdin, and only mutates primary-DB operation UUIDs that it created in this run. Test hooks/observation use separate authorized credentials; they do not imply the simulated primary DBA has those credentials. No `DROP`, `TRUNCATE`, schema edits, volume removal or unrelated-row mutations are used. One test deliberately deletes its own primary transaction row; the independent issuance and audit evidence are retained. Missing-before-publication and deletion-after-verification are reported as distinct scenarios, not conflated.

Results live in `.local/scenarios-<timestamp>-<id>/report.json`. Any failed prerequisite/check yields a nonzero exit code and is not reported as a successful run. The suite stops after its first failure so later tests do not disguise an inconsistent state. After testing, stop and restart without `-EnableTestFaults` for normal use.

## Real service outage and recovery

After `verify-local.ps1` finishes, run separately (never concurrently with another test or presentation):

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ".\scripts\recovery.ps1"
```

The wrapper first runs 9 process-ownership and 5 child-lifecycle regression tests. The live harness creates fresh fixtures and stops only recorded, verified service JVMs:

- Processor outage: a request returns HTTP 503 after issuance/publication; there are no postings while the processor is stopped. After restart, the durable queue executes the original operation without client republication; same-key retries do not create another debit.
- Key-service outage: a NEW operation returns HTTP 503 without issuance, source publication or balance changes. After restart, the same-key retry succeeds exactly once.
- Exact protected payloads, debit/credit legs, balances and durable audit outcomes are checked. `finally` restarts the demo in normal mode and verifies that fault hooks return HTTP 404.

Reports and command logs are retained under `.local/recovery-*/`. A false or incomplete report is not a pass. These are local process outages, not power-loss, database destruction, production failover or a test of key-service loss during an already verified in-flight settlement.

The launcher distinguishes child exit from closure of inherited output pipes; Node documents that these are [different lifecycle events](https://nodejs.org/api/child_process.html#event-close). Output draining after exit is bounded, without killing background service JVMs. A forced termination of the harness can still prevent `finally`; inspect the report and restore using `stop.ps1 -KeepDatabases` followed by `start.ps1 -SkipBuild` if needed.

## Two-database migration and embedded queue

The default Compose profile runs only `primary-db` and `audit-db`. Accounting tables share `audit_db` with receipts/history but use `settlement_processor`, separate from `audit_key` and `audit_processor`. Business calculations remain in Java. `SETTLEMENT_URL` is a logical accounting connection name, not a third physical database.

For an existing three-DB installation, stop the Live Lab, then run `powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\migrate-protected-db.ps1`. The script stops only tracked JVMs, verifies database identities, backs up both protected stores, copies seven tables without overwrite, compares rows and schema, then stops the legacy container without deleting its volume. A failed post-restore check requires explicit reviewed `-ResumeReport <local-report-path>`; it never re-imports existing tables. After new accounting writes, a rollback cannot blindly switch to stale legacy balances.

The broker starts automatically in the processor JVM. `BROKER_DIRECTORY` points to protected `.local/activemq`; it is not a build directory. No broker TCP port or console is exposed. Do not delete this journal while processing or include it in release packages. Broker failure defers publication; durable protected jobs remain the execution authority.
