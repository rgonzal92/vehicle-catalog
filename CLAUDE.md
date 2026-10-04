## Agent skills

### Issue tracker

Issues are tracked in this repo's GitHub Issues via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

The five default triage roles, each label equal to its name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.

## Working rules

### Ticket branches

Build each ticket on its own branch; create it without asking. When the work is done, stop and show the diff. Commit, push, and open the pull request after approval, with a Conventional Commits message. The maintainer merges.

### Migrations

Every database migration leaves the previously deployed image able to run: additions only, and a removal waits one release.

### Wording

Text in the repository (code, docs, tickets, commit messages) describes the outcome it delivers. Delivery phases and planning documents stay out of it.
