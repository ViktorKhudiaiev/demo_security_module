# Publication sources

This directory owns the customer-facing documentation generators.

- `customer-demo.template.html`, `walkthrough-data.mjs`, `build-walkthrough-diagrams.mjs`: current two-database UI and compact sequence.
- `build-demo.mjs`: builds `docs/index.html` from the sources and selected public evidence.
- `build-reading-views.mjs`: Markdown article/handbook to offline HTML.
- `build-article-pdf.py`: article HTML to the deliverable PDF.
- `build-deck.mjs`: editable native PowerPoint shapes/tables plus notes. Private renders and validation receipts belong in ignored `.local/artifact-qa/presentation-two-db/`.
- `publish-current-evidence.mjs`: explicit passing-report selection with SHA-256 provenance. It refuses to overwrite published evidence.
- `validate-content.py`: language-content gate, excluding runtime evidence and distributions.
- `package-materials.py`: allow-listed, hash-manifested offline ZIP; no runtime secrets, service binaries or databases.

Keep dated measurements unchanged. A new run is additional evidence, not a reason to relabel old measurements. PowerPoint finalization uses exclusive output/receipt paths; supply a new `DECK_OUTPUT_NAME` and `DECK_RECEIPT_NAME` for a new candidate revision, then review and update the public links. Do not set `DECK_EDIT_SOURCE` for an architecture edit: a notes-only import would preserve obsolete visible slides.

The local Live Lab server is separate, under `docs/live/`; publication generation does not run financial scenarios.

Run from the repository root with Node.js 20+ and Python available:

```powershell
node docs/build/build-demo.mjs
node docs/build/build-reading-views.mjs
node --test docs/build/offline.test.mjs docs/live/live-demo.test.mjs
python docs/build/validate-content.test.py
python docs/build/package-materials.py
python docs/build/validate-content.py
```

The reading-view generator uses the existing bundled `marked` dependency. PDF and slide generation additionally require the document runtimes described in their scripts. Reading the generated HTML requires none of these tools.

`offline.test.mjs` checks local companion links and executes inline walkthrough logic with networking disabled. It also checks that the optional lab stays disconnected when opened from disk. These are source/runtime checks, not a browser rendering claim.
