# Replay Safety & Non-Filesystem Shared State

Replaying or retrying a workflow **must not** duplicate or corrupt **external**
side effects, and shared mutable state **beyond** the filesystem **must not** be
mutated unsafely by concurrent agents. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §15 R8, §8.5).

## When this applies

Worktree isolation ([[worktree-workflow]]) covers the **filesystem only**. This
rule governs everything else an agent can touch that outlives or escapes its
worktree:

- **External side effects** — creating/updating tickets or issues, posting
  comments, sending messages/notifications, provisioning or changing
  infrastructure, writing to datastores, triggering deployments or pipeline runs,
  publishing artefacts.
- **Non-filesystem shared resources** — databases, infrastructure, external
  services, ticket systems, message queues, and any other mutable state two
  sessions could write to at once.

If a task only edits files inside its own worktree, this rule does not apply — the
worktree boundary already contains it.

## Replay safety (R8)

A workflow may be **retried** (after a transient failure) or **replayed** (to
reproduce or re-evaluate a result, [[decision-log]] / spec §15 R6). Either path
**MUST NOT** duplicate or corrupt external side effects. For every action with an
external side effect, the agent **MUST** ensure one of:

- **Idempotent** — re-running produces the same end state (e.g. an upsert keyed on
  a stable id, "create ticket if absent", a `PUT` to a known resource). Prefer
  this.
- **Guarded against re-execution** — a dedupe key, conditional/`If-Match`
  precondition, advisory lock, or recorded "already done" marker prevents a second
  application.
- **Explicitly marked non-replayable** — the action is recorded as having an
  irreversible external effect, so replay tooling and any retry logic **MUST**
  skip it rather than blindly re-run it. The marking **MUST** be recorded
  ([[decision-log]]).

An agent **MUST NOT** retry a side-effecting action whose idempotency or guard it
cannot establish; instead it **MUST** escalate ([[operating-model]] failure
handling). Note this is narrower than retry boundedness in [[liveness]]: even a
*first* retry of an unguarded side effect is unsafe.

## Non-filesystem shared state (§8.5)

Because worktrees do not isolate these resources, the system **MUST** apply the
same anti-contention discipline it applies to files:

1. **Identify during decomposition.** Any shared non-filesystem resource a unit
   will mutate **MUST** be identified when the work is decomposed
   ([[operating-model]] decomposition / spec §7.3), as part of the unit's
   delegation metadata.
2. **Feed the concurrency limit.** Contention on these resources **MUST** lower
   the dynamic concurrency limit ([[operating-model]] parallelism limits / spec
   §8.2) — two units that would write the same database, queue, or ticket project
   **MUST NOT** run concurrently unless their access is provably disjoint.
3. **Prevent unsafe concurrent mutation** via, in order of preference:
   - **Partitioning** — give each unit a disjoint key space / namespace / prefix
     so they never touch the same record;
   - **Dedicated non-production targets** — point each session at its own
     throwaway DB schema, queue, or sandbox project instead of a shared one;
   - **Locking** — serialise access to the resource with an explicit lock;
   - **Serialisation** — sequence the contending units so only one mutates at a
     time (last resort, since it removes the parallelism).

Reads of shared state are generally safe; the requirement is about **mutation**.

## On failure

If safe concurrent mutation cannot be guaranteed, or a retry/replay cannot be made
side-effect-safe, the agent **MUST** fail safe, **MUST NOT** proceed with the
unguarded mutation, and **MUST** escalate with the reason recorded
([[operating-model]], [[decision-log]]). A reviewer who finds a side-effecting
action that is neither idempotent, guarded, nor explicitly marked non-replayable
**MUST** raise it as a CRITICAL finding under [[review-constitution]] and block
reintegration.
