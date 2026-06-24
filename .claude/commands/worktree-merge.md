---
description: Merge the current worktree's work back to master through the verification chain, locked rebase, and post-rebase integration re-verify + review
---
Follow the worktree merge workflow in `.opencode/rules/worktree-workflow.md` (the reintegration steps). Do not skip any gate unless the user explicitly says "skip gate X".

The pre-rebase gates (1–4) review *this worktree's own changes*; the locked rebase then produces the **integrated** result, which MUST itself be re-verified (§8.4) and re-reviewed adversarially under the `review-integration` constitution (§14.6) **before it is pushed to master** — the integration tier gates reintegration (§14.6 LR4), so the push happens only after both pass.

Run the pre-rebase gates (1–4) in order; then, **holding the merge lock**, rebase, re-verify the integrated tree, run the integration review, and **push only on PASS**, then clean up. **Any failure stops the merge** and leaves the worktree intact for inspection — never delete a worktree that still holds unreintegrated work (§8.3).

1. **Sanity check** — confirm CWD is inside a worktree (`git rev-parse --git-common-dir` differs from `.git`). If not, refuse and tell the user to `/worktree` first.

2. **Gate 1 — Tests.** Detect changed modules from `git diff --name-only origin/master...` and run targeted Maven tests **plus mockserver-core always**:
   ```bash
   ./mvnw verify -pl :mockserver-core $(...) -DforkCount=1 -DreuseForks=false -fae -Djacoco.skip=true
   ```
   For long runs, launch the build in the background (`Bash run_in_background`, or `./cmd > log 2>&1 &`) and poll the log.

3. **Gate 2 — Lint / checkstyle / type checks.** Maven's `validate` phase runs checkstyle. For frontend changes also run `npm run lint && npm run build`.

4. **Gate 3 — Adversarial review (unit/Tier-1).** Spawn a `review-final` subagent on `git diff origin/master...HEAD` — this reviews *this worktree's own changes*, pre-rebase. Block on BLOCK verdict. Quote any concerns back to the user. (The integration review of the merged result is step 7, after the rebase.)

5. **Gate 4 — Summary & proceed.** Under the DVRR operating model (`.opencode/rules/operating-model.md`), gates 1–3 are the authority — they replace human pre-approval. Show:
   - `git diff --stat origin/master...HEAD`
   - List of changed files
   - Gate 1/2/3 results in one line each
   Then **proceed automatically** to the locked rebase. Do NOT wait for approval. Fail-closed: if any of gates 1–3 did not return a clean PASS, stop and leave the worktree intact. A user can interject at any time to halt.

6. **Acquire the merge lock and rebase — do NOT push yet.** The lock lives in the **shared git common dir** (`LOCK_DIR="$(git rev-parse --git-common-dir)/agent-rebase.lockdir"`) — not `.git/…`, because inside a linked worktree `.git` is a *file* and a per-worktree path would not serialise across sessions. Acquire a **held** lock (`mkdir "$LOCK_DIR"`, atomic POSIX mutex; or `flock` with a descriptor kept open across steps 6–9 — *not* a self-contained `flock bash -c`, which would release before the review runs). Record the holder so `/agent-status` can attribute it: `printf '%s %s %s\n' "$(git rev-parse --show-toplevel)" "$$" "$(date +%s)" > "$LOCK_DIR/holder"`. Check the operator halt first (`.opencode/scripts/check-halt.sh`). Holding the lock, produce the integrated result locally:
   ```bash
   git fetch origin master --quiet
   git rebase origin/master    # no push yet — steps 7–8 must PASS first
   ```
   The lock is held through steps 7–9 so the pushed result is exactly the result that was re-verified and integration-reviewed. This serialises merges (correctness/safety over throughput, O1/O9 > O3); a second session waits up to 5 minutes and retries. Every abort path below MUST release the lock.

7. **Re-verify the integrated tree, under the held lock (§8.4).** A clean rebase can still introduce *semantic* breakage (interface drift, a symbol another session renamed, a duplicated change). Re-run Gate 1's targeted module tests plus mockserver-core, and Gate 2's lint/type checks, against the **rebased `HEAD`** (not the pre-rebase commits). If this fails, do NOT push — release the lock and leave the worktree intact for inspection.

8. **Integration adversarial review, under the held lock (§14.6).** Spawn a *separate, freshly-spawned, clean-context* reviewer under the **`review-integration`** constitution (NOT a generic `review-final`, and NOT the implementer or a context-anchored continuation of it) on the integrated diff (`git diff origin/master...HEAD` after the rebase), giving it the step-7 re-verify results and the plan/wave scope. It probes the Tier-2/3 concerns: combination defects, interface drift, duplicated/conflicting changes, dropped units, end-to-end coherence vs the plan. Loop to convergence or the 8-iteration cap (§14.5). **On BLOCK:** do not push; release the lock; leave the worktree for fix-forward.

9. **Push the gated result, then release the lock.** Only after steps 7 and 8 PASS, still holding the lock:
    ```bash
    git push origin HEAD:master
    rm -rf "$LOCK_DIR"    # release (also on any abort above; rm -rf since the holder file makes it non-empty)
    ```
    If the push is rejected because `master` advanced during the gate, release the lock and restart from step 6 (re-fetch, re-rebase, re-gate) — never force-push.

10. **Cleanup.** Remove the worktree **promptly** once its work has cleanly reintegrated (gates 1–4 + rebase + step-7 re-verify + step-8 `review-integration` PASS + step-9 push landed) **or** the work is explicitly abandoned. A worktree still holding **unreintegrated** changes (failed/blocked gate, aborted rebase, unpushed work) MUST NOT be removed — surface it for disposition (it appears in `/agent-status`) and let the user decide. Cleanup MUST NOT discard unmerged work without an explicit, logged decision (§8.3). The guard below ensures removal happens only after the branch has landed on `master`:
    ```bash
    git fetch origin master --quiet
    if ! git merge-base --is-ancestor HEAD origin/master; then
        echo "Worktree holds unreintegrated work — NOT removing; surfacing for disposition (see /agent-status)." >&2
        exit 1
    fi
    cd "$(git rev-parse --show-toplevel)/.."
    git worktree remove "${WORKTREE_DIR}" --force
    git branch -D "${BRANCH}"
    rm -f .tmp/active-worktree
    ```

11. **Report.** Summarise to the user: gate results (incl. integration re-verify + `review-integration` verdict), commit hash on master, worktree cleaned up — or, if it held unreintegrated work, that it was preserved and surfaced for disposition.

If the user provided additional instructions (e.g. "skip user approval", "skip tests because we already ran them"): $ARGUMENTS — only honour explicit skip flags, never silently drop a gate.
