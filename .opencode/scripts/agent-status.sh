#!/usr/bin/env bash
# agent-status.sh — render a table of active agent worktrees and their state.
#
# Reads each `.worktrees/agent-*` directory under the repo root and prints:
#   - agent ID (suffix of the worktree dir name)
#   - branch
#   - age (minutes since worktree creation)
#   - activity (first line of `.tmp/agent-activity` inside the worktree, if present)
#   - commit count ahead of origin/master
#   - lock status (* if this worktree holds the merge lock — the directory
#     "agent-rebase.lockdir" in the shared git common-dir, $(git rev-parse --git-common-dir))
#
# Companion to `/worktree` and `/worktree-merge`. See
# `.opencode/rules/worktree-workflow.md` for the broader workflow.
#
# Usage:
#   .opencode/scripts/agent-status.sh
#
# No arguments. Exits 0 even if no worktrees are active (prints a short notice).

set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null)"
if [ -z "${REPO_ROOT}" ]; then
    echo "Not inside a git repository" >&2
    exit 1
fi

WORKTREE_BASE="${REPO_ROOT}/.worktrees"
# Canonical merge lock is a held directory mutex (see worktree-workflow.md Step 7),
# living in the SHARED git common dir so it serialises across all worktrees (inside
# a linked worktree ".git" is a file, not a dir). Existence == lock held; its
# `holder` file records "<worktree-path> <pid> <epoch>". (A mkdir mutex has no fd
# holder, so lsof cannot detect it — read the directory + marker instead.)
GIT_COMMON_DIR="$(cd "${REPO_ROOT}" && git rev-parse --git-common-dir 2>/dev/null || echo "${REPO_ROOT}/.git")"
case "${GIT_COMMON_DIR}" in /*) ;; *) GIT_COMMON_DIR="${REPO_ROOT}/${GIT_COMMON_DIR}" ;; esac
LOCK_DIR="${GIT_COMMON_DIR}/agent-rebase.lockdir"

if [ ! -d "${WORKTREE_BASE}" ]; then
    echo "No agent worktrees active (${WORKTREE_BASE} does not exist)."
    echo "Start or resume work in a worktree with /worktree — no work runs in the bare checkout (spec C4 / §8.3)."
    exit 0
fi

# Count worktree dirs (excluding nothing — be cheap)
shopt -s nullglob
WORKTREE_DIRS=("${WORKTREE_BASE}"/agent-*)
shopt -u nullglob

if [ "${#WORKTREE_DIRS[@]}" -eq 0 ]; then
    echo "No agent worktrees active."
    exit 0
fi

# Detect the held merge lock (directory mutex) and its recorded holder.
# Held == the lock directory exists. The holder marker (best-effort, written by
# the merge flow) names the holding worktree and PID; absence just means the
# holder wasn't recorded, not that the lock is free.
LOCK_HELD=""
LOCK_HOLDER_WT=""
LOCK_HOLDER_PID=""
if [ -d "${LOCK_DIR}" ]; then
    LOCK_HELD="yes"
    if [ -f "${LOCK_DIR}/holder" ]; then
        LOCK_HOLDER_WT="$(awk 'NR==1 {print $1; exit}' "${LOCK_DIR}/holder" 2>/dev/null || true)"
        LOCK_HOLDER_PID="$(awk 'NR==1 {print $2; exit}' "${LOCK_DIR}/holder" 2>/dev/null || true)"
    fi
fi

now_epoch="$(date +%s)"

printf "%-26s  %-44s  %-8s  %-32s  %-7s  %s\n" \
    "ID" "BRANCH" "AGE" "ACTIVITY" "COMMITS" "LOCK"
printf -- '-%.0s' {1..130}
echo

for wt in "${WORKTREE_DIRS[@]}"; do
    [ -d "${wt}" ] || continue
    agent_id="$(basename "${wt}" | sed 's/^agent-//')"
    branch="$(git -C "${wt}" rev-parse --abbrev-ref HEAD 2>/dev/null || echo 'unknown')"

    # Worktree creation time — try macOS stat first, then GNU stat
    created_epoch="$(stat -f %B "${wt}" 2>/dev/null || stat -c %W "${wt}" 2>/dev/null || echo 0)"
    if [ "${created_epoch}" -gt 0 ]; then
        age_min=$(( (now_epoch - created_epoch) / 60 ))
        age="${age_min}m"
    else
        age="?"
    fi

    activity="(idle)"
    if [ -f "${wt}/.tmp/agent-activity" ]; then
        # Truncate to 32 chars to keep the table aligned
        activity="$(head -1 "${wt}/.tmp/agent-activity" | cut -c1-32)"
    fi

    # Commits ahead of master
    commits="$(git -C "${wt}" rev-list --count origin/master..HEAD 2>/dev/null || echo '?')"

    # Lock indicator: mark "*" if the recorded holder of the merge lock is this
    # worktree (matched by basename, since the holder path may be relative).
    lock="-"
    if [ -n "${LOCK_HOLDER_WT}" ] && [ "$(basename "${LOCK_HOLDER_WT}")" = "$(basename "${wt}")" ]; then
        lock="*"
    fi

    printf "%-26s  %-44s  %-8s  %-32s  %-7s  %s\n" \
        "${agent_id}" "${branch}" "${age}" "${activity}" "${commits}" "${lock}"
done

if [ -n "${LOCK_HELD}" ]; then
    echo
    if [ -n "${LOCK_HOLDER_PID}" ] || [ -n "${LOCK_HOLDER_WT}" ]; then
        echo "Merge lock held by PID ${LOCK_HOLDER_PID:-?} (${LOCK_HOLDER_WT:-unknown worktree}) — a merge is rebasing/re-verifying/integration-reviewing."
    else
        echo "Merge lock is currently held (${LOCK_DIR} present) — a merge is in progress."
    fi
fi
