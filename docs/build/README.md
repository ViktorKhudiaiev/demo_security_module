# Publication sources

This directory owns the customer-facing documentation generators.

- `customer-demo.template.html`, `walkthrough-data.mjs`, `build-walkthrough-diagrams.mjs`: current two-database UI and compact sequence.
- `build-demo.mjs`: builds `docs/index.html` from the sources and selected public evidence.
- `build-reading-views.mjs`: Markdown article/handbook to offline HTML.
- `build-article-pdf.py`: article HTML to the deliverable PDF.
- `build-deck.mjs`: imports the existing editable PowerPoint template, updates named shapes/native tables and speaker notes, and validates a new candidate. Private renders and receipts belong in ignored `.local/artifact-qa/presentation-notifications-2026-09-08/`.
- `publish-current-evidence.mjs`: explicit selected-field publication with SHA-256 provenance. Passing and failing results are preserved without changing their acceptance thresholds. It refuses to overwrite published evidence.
- `validate-content.py`: language-content gate, excluding runtime evidence and distributions.
- `package-materials.py`: allow-listed, hash-manifested offline ZIP; no runtime secrets, service binaries or databases.

Keep dated measurements unchanged. A new run is additional evidence, not a reason to relabel old measurements. PowerPoint finalization uses exclusive output/receipt paths; supply new `DECK_QA_DIRECTORY`, `DECK_OUTPUT_NAME` and `DECK_RECEIPT_NAME` values for a new candidate revision, then visually review it before replacing the public `demo.pptx`. `DECK_EDIT_SOURCE` defaults to the existing public deck. The generator checks its expected 14-slide template and named shapes; a different template requires explicit adaptation, not a notes-only replacement. Runtime locations can be supplied through `RUNTIME_NODE_MODULES`, `RUNTIME_PYTHON` and `PRESENTATIONS_SKILL_DIR`.

Publish a separately dated verification result without requiring unrelated recovery runs:

```powershell
node docs/build/publish-current-evidence.mjs .local/verification-RUN_ID local-verification-YYYY-MM-DD.json
```

The publisher reads only the three explicit verification reports, removes workstation paths and unselected runtime fields, retains raw SHA-256 digests, and never copies the runtime directory. A failed measurement is publishable evidence, not a reason to select an older success as the current result.

The local Live Lab server is separate, under `docs/live/`; publication generation does not run financial scenarios.

Run from the repository root with Node.js 20+ and Python available:

```powershell
node docs/build/build-demo.mjs
node docs/build/build-reading-views.mjs
node --test docs/build/offline.test.mjs docs/build/evidence-publication.test.mjs docs/live/live-demo.test.mjs
python docs/build/validate-content.test.py
python docs/build/package-materials.py
python docs/build/validate-content.py
```

The reading-view generator uses the existing bundled `marked` dependency. PDF and slide generation additionally require the document runtimes described in their scripts. Reading the generated HTML requires none of these tools.

Generate the PDF after committing its source Markdown and evidence, using
`python docs/build/build-article-pdf.py --source-revision <full-commit-sha>`.
Relative article links become immutable GitHub links to that source commit in
the PDF; HTML and Markdown retain their portable local links. The article also
identifies the separate implementation snapshot used for the notification review.

`offline.test.mjs` checks local companion links and executes inline walkthrough logic with networking disabled. It also checks that the optional lab stays disconnected when opened from disk. These are source/runtime checks, not a browser rendering claim.

The September 8 post-publication validation passed 49 Node checks across the complete suite, including two new offline evidence checks and three publisher regressions. This is separate from the notification implementation's earlier 44-check run. Failed acceptance reports remain displayable with their actual booleans and measurements; the offline Evidence tab separates the latest failed throughput gate, historical successful benchmark and local email-capture functionality. No publication check reruns financial throughput or establishes external email delivery.
