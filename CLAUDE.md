# Project instructions — delFIN (personal-suite monorepo)

## Committing

Standing permission to commit without asking each time: once a self-contained
piece of functionality is implemented and verified (builds/tests pass,
smoke-tested against the running app), commit it — one commit per well-scoped
feature/fix/increment, matching the commit-message style already used in this
repo's history (see `git log`).

This does NOT cover pushing — always ask before `git push`, every time.

It also doesn't relax anything else in the global git safety rules: still
never force-push, never skip hooks, never commit files that look like they
contain secrets, and always show/summarize what's being committed.
