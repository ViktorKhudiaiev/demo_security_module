# Documentation

Open **[index.html](index.html)** directly in your browser. No application, server, build step or network connection is needed for the walkthrough. Keep this folder intact for the linked article, slides, guide and reference documents.

For implementation details, start with [Code structure and reading guide](reference/code-structure.md), then [Architecture](reference/architecture.md), [Protocol](reference/protocol.md) and [Local runbook](reference/running.md). The source guide distinguishes transport DTOs, immutable business/protocol models and the explicit JDBC schemas.

| Folder | Contents |
|---|---|
| `article` | Article source, HTML and PDF |
| `presentation` | Current PowerPoint, presenter guide and notes |
| `reference` | Code structure, protocol, architecture, glossary, requirements and runbook |
| `evidence` | Dated, sanitized measurements; never relabeled or edited |
| `history` | Superseded designs and review history |
| `downloads` | Ready-to-share documentation ZIP |
| `live` | Optional live lab; execution requires the running local application |
| `build` | Maintenance sources and documentation checks, not needed for reading |

The ZIP is an explicit public subset, not a copy of the project or local databases. It excludes backend helpers, runtime credentials, key files and private build records. Follow [build/README.md](build/README.md) only when updating the documents.
