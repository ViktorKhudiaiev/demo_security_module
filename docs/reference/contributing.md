# Contributions and protected-branch policy

The repository is public so readers can inspect and clone the code. Public access does not grant permission to push changes to the upstream repository. External contributors should fork it, create a branch and submit a pull request targeting `master`.

## Owner-controlled changes

- `master` accepts changes only through pull requests.
- Direct pushes, force pushes and deletion of `master` are prohibited by a baseline ruleset with no bypass actors.
- A separate owner gate restricts updates to the repository owner, `@ViktorKhudiaiev`, through pull requests only. Other users cannot merge into `master`, even if they later receive general write access.
- The review policy requests one approval, requires code-owner review and dismisses stale approvals after new commits. `.github/CODEOWNERS` assigns every path, including the policy itself, to the owner.
- The owner has a pull-request-only exception to the owner/review gate. GitHub does not allow authors to approve their own pull requests. The owner can therefore explicitly accept and merge their own proposal. The exception also applies when the owner deliberately merges another contributor's PR; it is tied to the owner's identity, not to PR authorship. This exception cannot bypass the separate baseline against direct pushes, force pushes or deletion.

The owner gate applies independently of `CODEOWNERS`. Code-owner matching starts when that file exists in the pull request's base branch. During the initial rollout, only the owner can merge, and the first reviewed merge installs the file in `master`.

GitHub settings are authoritative; a policy file by itself cannot enforce these controls. The repository owner can change the rules or permissions, and an attacker with control of the owner's GitHub account remains outside this governance guarantee. Owner acceptance is an explicit merge action; a formal GitHub approval review is not mandatory when the owner deliberately uses the documented exception.

## Before proposing a change

Keep project content in English, preserve dated runtime evidence, and never publish `.local`, credentials, private keys or databases. Run the relevant tests and `docs/build/validate-content.py` before sharing artifacts. The operational commands and measured scope are documented in [running.md](running.md) and [architecture.md](architecture.md).

GitHub references: [Rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets), [bypass permissions](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository), and [code owners](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners).
