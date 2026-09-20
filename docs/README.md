# Documentation

Open **[index.html](index.html)** directly in your browser. No application, server, build step or network connection is needed for the walkthrough. Keep this folder intact for the linked article, slides, guide and reference documents.

For implementation details, start with [Code structure and reading guide](reference/code-structure.md), then [Architecture](reference/architecture.md), [Protocol](reference/protocol.md) and [Local runbook](reference/running.md). The source guide distinguishes transport DTOs, immutable business/protocol models and the explicit JDBC schemas.

[Incident notifications](reference/notifications.md) explains the protected email outbox, retries and local Mailpit inbox. Capture is not external Gmail delivery. The [September 8 notification integration](evidence/notification-verification-2026-09-08.json) passed four local cases; it is not a new throughput result. The [latest throughput report, September 7](evidence/local-verification-2026-09-07.json), remains **FAILED** at 19.9636 steady completed TPS against the strict 20 TPS threshold, despite all 2,400 operations completing correctly. The [September 6 pass](evidence/local-verification-2026-09-06-final.json) remains historical evidence.

| Folder | Contents |
|---|---|
| `article` | Article source, HTML, PDF, LinkedIn publishing kit, cover image, Medium exports and publishing guide |
| `presentation` | Current PowerPoint, presenter guide and notes |
| `reference` | Code structure, protocol, architecture, glossary, requirements and runbook |
| `evidence` | Dated, sanitized measurements; never relabeled or edited |
| `history` | Superseded designs and review history |
| `downloads` | Ready-to-share documentation ZIP |
| `live` | Optional live lab; execution requires the running local application |
| `build` | Maintenance sources and documentation checks, not needed for reading |

The ZIP is an explicit public subset, not a copy of the project or local databases. It excludes backend helpers, runtime credentials, key files and private build records. Follow [build/README.md](build/README.md) only when updating the documents.
