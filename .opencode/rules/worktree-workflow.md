# Worktree Workflow — Default Per-Session Isolation With Rebase Lock

## Rule

Every **independent session** — the primary interactive session, any
parallel Claude/opencode window, and every long autonomous run — works
inside its **own dedicated git worktree** on a **local-only branch**,
not the main checkout. Start in a worktree (via `/worktree`) at session
start. **This holds even when the session makes no changes** —
read-only investigation, analysis, and review work in a worktree too;
the rule is *no work runs in the bare main checkout*, not *only
change-making work is isolated*. Changes return to `master` only via a
gated merge using `flock` to serialize concurrent rebases.
Isolation-by-default is what lets concurrent sessions operate on the
same repo without stepping on each other, and matches the AI-in-SDLC
spec (`docs/operations/ai-sdlc-integration-spec.md` §8.3).

**Delegate, don't do — and delegating is how routing happens.** The
primary session's job is to *orchestrate* (see [[operating-model]] and
[[subagent-routing]]): it should hand almost all execution —
implementation *and* investigation — to subagents rather than doing it
inline, because a subagent is where the correct **model, temperature,
and reasoning effort** are selected for the task. Those subagents are
**helper subagents** and so share this session's worktree (next
paragraph); the point is that the work still lands in *this* worktree,
just run by a right-sized subagent.

**Helper subagents are the one deliberate exception.** A subagent
spawned by a primary via the `Agent` tool **shares the primary's tree**
and is *not* isolated — this is required for correctness, not
convenience: helper subagents (reviewers, investigators) read the
primary's **uncommitted in-flight diff**; a worktree branched off
`origin/master` would see only committed state and miss the very changes
they exist to analyse, silently breaking the commit gate chain.
Isolation is **between independent sessions, not within one**.

## When To Use This

| Situation | Use worktree? |
|-----------|---------------|
| **Primary interactive session** doing substantive work | **Yes (default)** — start in a worktree via `/worktree`. (This previously stayed in the main checkout for IDE visibility; that exception is gone now that IntelliJ integration has been dropped.) |
| **Primary session doing read-only work** (investigation, analysis, answering a question, reviewing) that makes no changes | **Yes** — still a worktree. The rule is *no work in the bare checkout*; isolation is not contingent on whether files change. The merge ceremony simply has nothing to merge (skip to cleanup). |
| **Subagents spawned from the main session** via the `Agent` tool | **No separate worktree — share the spawning session's checkout.** Helper subagents read/analyse in-flight work (uncommitted edits the primary just made). A worktree based on `origin/master` would only see committed state and miss the live changes — and would break the review gate, which reviews the primary's uncommitted diff. They are still "in a worktree" — the primary's. |
| **A second, independent Claude/opencode window** for parallel work on the same repo | **Yes** — that session invokes `/worktree` at start. Each independent session gets its own worktree |
| Long autonomous task (`/loop`, `/schedule`, "go work on X and come back when done") | **Yes** — invoke `/worktree` at session start |
| Trivial one-off (typo, comment, doc one-liner) in a quick session | **Yes — still a worktree** (cheap: it shares the `.git` object store). What scales down for a trivial change is the *merge ceremony* (the 4-gate chain), not the isolation — see [[operating-model]], Scale The Ceremony |

**Key principle**: worktrees provide isolation **between independent sessions** (different Claude windows / autonomous runs), not **within a session** (a primary + its helper subagents). Helper subagents inherit the primary's filesystem state so they can see its uncommitted work.

## Workflow

```
1. /worktree              ──→  Agent creates worktree, switches CWD into it
2. Agent makes changes          (commits as it goes, all on the worktree branch)
3. Agent runs tests             (verification gate 1)
4. Agent runs lint/checkstyle   (verification gate 2)
5. Agent spawns review-final    (verification gate 3 — unit/Tier-1, this worktree's diff)
6. Agent shows diff summary      (verification gate 4 — summarise & proceed)
7. Acquire held lock + rebase    (produce integrated result; NO push yet)
8. Re-verify integrated result   (under held lock, §8.4)
9. review-integration on merge   (under held lock, Tier-2/3 integration review, §14.6)
10. Push only on PASS, release   (the gated reintegration — LR4; push then frees the lock)
11. Cleanup                      (remove worktree only after a clean reintegration)
```

### Step 1 — Create the worktree

```bash
SHORT_ID="$(date +%Y%m%d-%H%M%S)-$(uuidgen | head -c 6)"
WORKTREE_DIR=".worktrees/agent-${SHORT_ID}"
BRANCH="agent/${SHORT_ID}"

# Ensure master is current
git fetch origin master --quiet
git worktree add --quiet -b "${BRANCH}" "${WORKTREE_DIR}" origin/master
cd "${WORKTREE_DIR}"
```

`.worktrees/` is gitignored (add to `.gitignore` if absent). The
worktree shares the `.git/` object store with main, so disk cost is
minimal. The agent records the worktree path in `.tmp/active-worktree`
so a session resumption can find it again.

### Step 2 — Work in the worktree

All edits, commits, builds, tests happen inside `WORKTREE_DIR`. The
agent commits incrementally on the worktree branch (no rebase to master
yet).

**Attributable work artefacts (§8.3).** Temporary/scratch artefacts —
`.tmp/` scratch files, activity files, intermediate build logs — live
**inside this worktree** (e.g. `WORKTREE_DIR/.tmp/`), so they are
namespaced by the worktree / agent ID and are attributable to the agent
and task that produced them. Do not write scratch to the bare checkout's
`.tmp/` or to a shared system temp dir; keeping them worktree-local also
means they are cleaned up with the worktree (Step 10).

#### Activity recording for `/agent-status`

When multiple agent sessions run in parallel, the user can run
`/agent-status` to see a table of all active worktrees. To surface
**what each agent is currently doing** in that table, write a short
one-line description to `.tmp/agent-activity` inside the worktree
whenever the focus changes:

```bash
mkdir -p .tmp
echo "Running mvn verify on mockserver-netty" > .tmp/agent-activity
# ... later ...
echo "Reviewing diff for commit" > .tmp/agent-activity
```

The convention is intentionally lightweight: a single line, free
text, overwritten on each update. No state schema, no JSON. The
dashboard truncates to 32 characters so keep the message tight.
If the file is absent, the dashboard shows `(idle)` — the agent
need not write it, but doing so makes parallel work observable.

### Steps 3–6 — Gates (run by `/worktree-merge`)

All four must pass; any failure stops the merge and leaves the worktree
intact for inspection.

**Gate 1 — Tests.** Run targeted Maven tests for the modules the
worktree touched, plus the full mockserver-core suite (always run, it's
the blast-radius gate):

```bash
CHANGED_MODULES="$(git diff --name-only origin/master... \
    | sed -n 's|^mockserver/\([^/]*\)/.*|\1|p' | sort -u)"
./mvnw verify -pl :mockserver-core \
    $(printf ' -pl :%s' ${CHANGED_MODULES}) \
    -DforkCount=1 -DreuseForks=false -fae \
    -Djacoco.skip=true
```

**Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase
already runs checkstyle. For frontend changes in `mockserver-ui` or
`mockserver-client-node`, also run `npm run lint` and `npm run build`
(strict tsc).

**Gate 3 — Adversarial review (unit/Tier-1).** Spawn the `review-final`
agent on the worktree's diff vs master. This is the **pre-rebase,
unit-level** review of *this* worktree's own changes. Block on BLOCK
verdict, proceed on PASS. (The **integration** review of the merged
result happens *after* the rebase, under `review-integration` — Step 9,
§14.6 — because combination defects only become visible once concurrent
work is pulled in.)

```
Agent(
    subagent_type="review-final",
    description="Adversarial review for worktree merge",
    prompt="Review the diff from `git diff origin/master...HEAD` in this worktree. Verdict must be PASS or BLOCK. Focus areas: correctness, security, MockServer conventions, missing tests."
)
```

**Gate 4 — Summary & proceed.** Under the [[operating-model]] (DVRR),
gates 1–3 (tests, lint, adversarial review PASS) are the authority to
merge — they replace human pre-approval. Show the diff summary (`git
diff --stat origin/master...`, list of changed files, gate-1/2/3
results), then **proceed automatically** to the merge. A user can
interject at any point to halt or amend. This gate is **fail-closed**:
if any of gates 1–3 did not return a clean PASS, do NOT merge — leave
the worktree unmerged for inspection.

### Step 7 — Acquire the merge lock and rebase (produce, do NOT yet push, the integrated result)

The rebase below brings concurrent work from `master` into this worktree;
its result is the **integrated** result that Steps 8–9 gate **before** it
reaches the shared trunk. **The push does NOT happen here.** The merge
lock is acquired now and **held across Steps 7–10** so that the result
pushed in Step 10 is exactly the result that was re-verified (Step 8) and
integration-reviewed (Step 9) — i.e. the integration tier genuinely
*gates reintegration* (§8.4, §14.6 LR4), rather than running after a push
has already landed the combination on master.

```bash
# Respect the operator halt before any reintegration (see [[operator-halt]]).
.opencode/scripts/check-halt.sh || { echo "operator halt engaged — not merging"; exit 1; }

# Acquire a HELD lock (released only in Step 10, after a PASS push or on abort).
# mkdir is an atomic POSIX mutex; the lock spans the agent-driven gate steps,
# so it is NOT a single flock bash -c (which would release before the review runs).
# The lock lives in the SHARED git common dir (not ".git/…": inside a linked
# worktree ".git" is a *file*, and a per-worktree gitdir would not serialise
# across sessions). git rev-parse --git-common-dir resolves it from any worktree.
LOCK_DIR="$(git rev-parse --git-common-dir)/agent-rebase.lockdir"; START=$(date +%s)
while ! mkdir "${LOCK_DIR}" 2>/dev/null; do
    [ "$(($(date +%s) - START))" -gt 300 ] && { echo "Merge lock held >5m by another session — retry shortly or check ${LOCK_DIR}/holder"; exit 1; }
    sleep 2
done
# Record the holder so /agent-status can attribute the lock (a mkdir mutex has no
# fd holder for lsof to find — see agent-status.sh). Format: "<worktree> <pid> <epoch>".
printf '%s %s %s\n' "$(git rev-parse --show-toplevel)" "$$" "$(date +%s)" > "${LOCK_DIR}/holder"
# From here the lock is held; every exit path below MUST release it with
#   rm -rf "${LOCK_DIR}"   (plain rmdir fails — the holder file makes it non-empty).
git fetch origin master --quiet
git rebase origin/master   # may resolve interactively if conflicts
# NOTE: no push yet — Steps 8–9 must PASS first.
```

The lock is held for the duration of the integration gate. This
intentionally serialises merges (correctness/safety over throughput,
spec O1/O9 > O3): a second session's merge waits up to 5 minutes for the
lock and retries. `flock --timeout 300 "${LOCK_DIR}.f"` may be used
instead where available, **provided** the same descriptor is held open
across Steps 8–10 (not a self-contained `flock bash -c`).

### Step 8 — Re-verify the integrated result, under the held lock (§8.4 / §14.6)

A clean rebase is **not** proof of a correct integration: concurrent
work pulled in from `master` can introduce *semantic* breakage even when
git reports no textual conflict (interface drift, a method another
session renamed, a duplicated change). So after the rebase brings in
concurrent work, **re-run the affected validations on the integrated
tree** — at minimum re-run Gate 1's targeted module tests plus
mockserver-core, and Gate 2's lint/type checks — against the rebased
`HEAD`, not the pre-rebase commits. This re-verification is mandatory
(§8.4) and **gates the push** (Step 10). If it fails, do not push and do
not remove the worktree; release the lock, leave the worktree intact for
inspection and disposition (§8.3 "cleanup MUST NOT discard unmerged
work"), and fix forward (a follow-up commit on the worktree branch,
re-running Steps 7–9).

### Step 9 — Integration adversarial review, under the held lock (§14.6)

The merge-gate adversarial review is the **Tier-2/3 integration
review**, not a generic final pass, and it runs **before the push** while
the lock is held. Spawn a *separate, freshly-spawned, clean-context*
reviewer — the **`review-final`** agent **applying the `review-integration`
constitution** (the constitution is a profile, not a separate agent) (§14.3) — given
the integrated diff (`git diff origin/master...HEAD` *after* the rebase),
the re-verification results from Step 8, and the plan/wave scope — to
probe what only becomes visible in the combination: **combination
defects, interface drift between units, duplicated or conflicting
changes, dropped units, and end-to-end coherence vs the plan's intent.**
This reviewer MUST NOT be the implementer or any context-anchored
continuation of it, and MUST NOT be a resumed `review-final` session
applying its default profile instead of the `review-integration`
constitution. Loop to convergence or the 8-iteration cap per §14.5. **On
BLOCK:** do not push; release the lock; leave the worktree for fix-forward
(re-run Steps 7–9). **On PASS:** proceed to Step 10.

### Step 10 — Push the gated result, then release the lock

Only now — after Step 8 re-verify PASS and Step 9 integration-review PASS,
still holding the lock from Step 7 — push the integrated result and
release the lock:

```bash
git push origin HEAD:master
rm -rf "${LOCK_DIR}"   # release the merge lock (also do this on any abort above; rm -rf because the holder file makes it non-empty)
```

If the push is rejected because `master` advanced while the gate ran
(another session merged after this rebase), release the lock and restart
from Step 7 (re-fetch, re-rebase, re-gate) — never force-push.

### Step 11 — Cleanup

Remove the worktree **promptly** once — and only once — its work has
been cleanly reintegrated to `master` (Steps 7–10 all PASS, push landed)
or the work has been explicitly abandoned (§8.3). A worktree that still holds
**unreintegrated** changes (a failed/blocked gate, an aborted rebase,
uncommitted or unpushed work) **MUST NOT** be removed: surface it for
disposition (it shows up in `/agent-status`) and let the user decide.
**Cleanup MUST NOT discard unmerged work without an explicit, logged
decision** — the `--force` below is safe only because reaching this step
means the branch was successfully pushed.

```bash
# Guard: only clean up after the branch has actually landed on master.
git fetch origin master --quiet
if ! git merge-base --is-ancestor HEAD origin/master; then
    echo "Worktree holds unreintegrated work — NOT removing. Surfacing for disposition (see /agent-status)." >&2
    exit 1
fi

cd "$(git rev-parse --show-toplevel)/.."  # navigate to the parent of the worktree directory
git worktree remove "${WORKTREE_DIR}" --force
git branch -D "${BRANCH}"
rm -f .tmp/active-worktree
```

## Concurrency Examples

### Two agents working in parallel

- Agent A creates `.worktrees/agent-20260528-141500-abc123`
- Agent B creates `.worktrees/agent-20260528-141512-def456`
- Both edit files independently in their own worktrees — no interference
- A finishes first, acquires the merge lock, rebases onto master,
  re-verifies + runs the integration review **under the held lock**, and
  pushes only on PASS, then releases the lock
- B finishes second, acquires the lock (waits while A holds it through its
  gate), rebases onto **updated** master (includes A's changes),
  re-verifies + integration-reviews, pushes on PASS
- If B's rebase finds conflicts with A's changes, B resolves them
  inside the worktree before pushing

### Lock contention timeout

- Agent C tries to acquire the merge lock while Agent D has held it >5m
  (something pathological — e.g., D is blocking on an interactive conflict
  resolution, or its integration review is stuck)
- C's `mkdir`-lock acquisition loop exceeds its 300s budget and exits non-zero
- C surfaces a clear message and stops; the user investigates D's worktree
  (and can read the `holder` file in `$(git rev-parse --git-common-dir)/agent-rebase.lockdir` to see who holds it)

## Failure Modes

| Failure | Outcome |
|---------|---------|
| Gate 1 (tests) fails | Worktree preserved; agent shows test failures; rebase blocked |
| Gate 2 (lint) fails | Same — fix lint and re-run merge |
| Gate 3 (review-final) returns BLOCK | Worktree preserved; agent shows review verdict; user decides |
| Gate 4 (a gate 1–3 not a clean PASS, or user interjects) | Worktree preserved; merge halted; user keeps the diff to iterate |
| Merge-lock timeout | Worktree preserved; agent reports lock holder (the `holder` file in `$(git rev-parse --git-common-dir)/agent-rebase.lockdir`); retry later |
| Rebase conflict | Worktree preserved; agent attempts resolution or hands back to user |
| Step 8 re-verify fails on integrated tree | Worktree preserved; push not finalised; integration breakage surfaced |
| Step 9 `review-integration` returns BLOCK | Worktree preserved; integrated diff shown; user decides |

The invariant: **no failed merge ever destroys work** (§8.3 — cleanup
MUST NOT discard unmerged work without an explicit, logged decision). A
worktree is removed (Step 11) **only** once its work has been cleanly
reintegrated to `master` — all of Gates 1–4, the locked rebase, the
under-lock re-verification (§8.4) and `review-integration` pass (§14.6),
and the gated push (Step 10) — or once the work is explicitly abandoned. A worktree still
holding unreintegrated changes is **never** removed; it is surfaced for
disposition via `/agent-status`.

## What This Replaces (Partially)

The "Parallel Session Safety" section in `AGENTS.md` lists rules that
exist because multiple sessions might step on each other in a shared
checkout:

- "Stage explicit paths only (never `git add .`)" — still good practice
- "Re-read files before editing" — still good, race conditions remain
- "Commit only files changed in this session" — still good
- "Run `git pull --rebase` before push" — obsolete inside a worktree
  (the merge step does this implicitly under the lock)

When using `/worktree`, the rebase-lock makes the last point automatic.
The first three remain good hygiene.

## Open Questions / Future Work

- **A real script wrapper.** The bash snippets above should be packaged
  as `scripts/agent-worktree-create.sh` and `scripts/agent-worktree-
  merge.sh`. Today the agent inlines them; later they become reusable.
- **Per-module test selection in Gate 1.** Currently runs core + touched
  modules. Could be smarter by parsing the dependency graph.
- **Diff size cap on Gate 4.** For very large diffs the summary
  presentation is awkward. Consider a "diff summary + risk score"
  presentation rather than raw diff dump.
