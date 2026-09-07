# Project language policy

All project-authored content must be in English. This includes source code,
comments, identifiers, tests, scripts, messages, UI, diagrams, documentation,
presentation slides, speaker notes and generated distribution packages.

Do not add a language switch or retain a second-language copy. Use English
document names and language metadata. The language of a user conversation does
not change the language of project deliverables.

Preserve technical meaning, requirement IDs, historical decisions, metrics and
source evidence when translating. Mark superseded policies as historical.
Do not modify captured runtime evidence, dependency distributions or Git history
to enforce this policy. Do not expose `.local` credentials, keys or databases.

Run `docs/build/validate-content.py` before sharing a release package.
