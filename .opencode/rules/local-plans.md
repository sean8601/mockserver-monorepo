# Local (Uncommitted) Plans — `docs/plans/*.local.md`

## Rule

Planning and working docs that should **NOT** be committed go in the
`docs/plans/` directory with a **`.local.md`** suffix.

- **Committed** plan/design doc: `docs/plans/<name>.md`
- **Local-only** plan/working doc: `docs/plans/<name>.local.md`

Both live in the same directory so plans are discoverable together, but
the `.local.md` files are never staged or committed.

## Why

1. **Gitignored by default.** `.gitignore` contains `*.local.md`, so any
   file ending in `.local.md` is excluded from `git status`, `git add`,
   and commits — no risk of accidentally committing a private working doc.
   (Verify with `git check-ignore -v docs/plans/foo.local.md`.)
2. **Co-located with real plans.** Unlike `.tmp/` scratch files, local
   plans sit next to the committed plans in `docs/plans/`, so they're easy
   to find and reference across sessions.
3. **Survives across sessions.** Use for brainstorms, in-flight design
   notes, feature backlogs, and session-resume docs that you want to keep
   but not publish.

## How to Use It

```text
# Local-only working doc — gitignored, safe for drafts and resume notes
docs/plans/website-seo-ga-gsc.local.md
docs/plans/sre-chaos-features.local.md

# Committed, shared plan — plain .md
docs/plans/mockserver-llm-mocking.md
docs/plans/security-defaults.md
```

When a local plan is ready to become an authoritative, shared design doc,
rename it from `<name>.local.md` to `<name>.md` and commit it through the
normal `/commit` workflow.

## Relationship to `.tmp/`

- `.tmp/` — throwaway scratch (logs, downloads, intermediate JSON). Not for
  documents you want to keep. See `.opencode/rules/tmp-directory.md`.
- `docs/plans/*.local.md` — durable working/plan docs you keep across
  sessions but don't commit.

## Cleanup

`.local.md` files are never authoritative. A stale one left by a previous
session can be safely deleted with `rm docs/plans/<name>.local.md`. When a
session-resume or plan doc is still relevant, the authoritative pointer to
it lives in the agent's `MEMORY.md` index — start there to find which local
plans are current.
