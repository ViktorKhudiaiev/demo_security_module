# Record Integrity

Authenticated PostgreSQL operations, independent evidence and protected execution. All funds are simulated; no real payments.

## Open the documentation — no installation or server

**Open [docs/index.html](docs/index.html) in a web browser.** After cloning or downloading this repository, double-click that file or use the browser's Open File command. The overview, architecture, sequence, terms and recorded evidence are embedded in the page. Java, Docker, Node.js and Internet access are not required to read it.

GitHub displays HTML source instead of running the page. Download the repository or [documentation ZIP](docs/downloads/demo-materials.zip), extract it, and open `docs/index.html` locally. Keep the complete `docs` folder together for companion links.

- [Article](docs/article/index.html) · [PDF](docs/article/article.pdf)
- [PowerPoint](docs/presentation/demo.pptx) · [Presenter guide](docs/presentation/guide.html) · [Speaker notes](docs/presentation/notes.md)
- [Architecture and acceptance criteria](docs/reference/architecture.md)
- [Protocol](docs/reference/protocol.md) · [Glossary](docs/reference/glossary.md)
- [Final recorded verification](docs/evidence/local-verification-2026-09-06-final.json)

The diagrams and results are a static explanation, not a live connection to a database. External RFC/vendor links need Internet only when opened.

## Repository layout

```text
docs/
  index.html          Offline customer walkthrough
  article/            Article source, browser version and PDF
  presentation/       Deck, guide and speaker notes
  reference/          Architecture, protocol, requirements and runbook
  evidence/           Sanitized dated test results
  history/            Superseded designs and review records
  downloads/          Portable documentation ZIP
  live/               Optional live lab page and its local helper
  build/              Documentation templates, generators and checks
account-transfer-app/ Trusted application service
tokenization-module/  Shared protocol and key service
transaction-security-module/ Integrity processor and embedded broker
scripts/              Application startup, migration and verification
.local/               Private runtimes, credentials, keys, backups and build files
```

Operational scripts are not presentation materials. Bundled JDK/Maven live in ignored `.local/tools/`; private document renders are in `.local/artifact-qa/`. Never distribute `.local`, `.m2-cache`, IDE state or Maven `target` directories.

## Optional live transactions

Reading the documentation does not start services. The separate [Live Lab](docs/live/index.html) explains how to run actual local experiments. This needs Docker Desktop, Java 21, Maven and Node.js 20+. Existing local runtimes are detected automatically; a fresh clone may use installed tools.

From PowerShell in the repository:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\live-demo.ps1 -StartStack
```

Then visit the loopback URL printed by the helper. Valid transfers, direct-primary forgery and retries use dedicated simulated accounts. The file opened from disk cannot execute these actions. Do not expose the helper or service APIs publicly.

## Verification and scope

The current model uses **Main + Audit/Protected PostgreSQL** and persistent ActiveMQ inside the processor JVM. Java calculates transfers; authoritative balances, postings and execution identity remain outside the Main DBA's authority. See the architecture document for the complete threat model and production requirements.

The September 6 final run passed 86 Java tests, 15 PostgreSQL scenarios and 2,400 unique completed transfers at 20 offered TPS for 120 seconds, with zero failures and zero configured throughput tolerance. Steady completed throughput was 20.0000 TPS; whole-run throughput was 19.9173 TPS; p95 was 3,057 ms. Missing unpublished receipt detection took 32.96 seconds within the declared 120-second observation budget. These are retained local measurements, not a production SLA.

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\verify-local.ps1 -DurationSeconds 120 -RateTolerance 0
```

Run recovery tests separately, never during a benchmark or presentation. Full commands, ownership safeguards, migration and dependency setup are in the [runbook](docs/reference/running.md). Historical evidence remains unchanged.

## Contributions

Read or clone this public repository to study the implementation. Propose changes through a fork and pull request. Public visibility does not grant write access. Changes to `master` require an explicit owner merge through a pull request; direct pushes, force pushes and deletion are blocked by repository rules. See the [contribution and review policy](docs/reference/contributing.md).
