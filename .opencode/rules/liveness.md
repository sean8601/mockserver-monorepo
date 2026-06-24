# Liveness & Termination

Every task and workflow **must** have a bounded budget and **must** terminate. The
orchestrator **must** detect non-progress and runaway loops and **stop, escalate,
and record** rather than consume budget indefinitely. Conforms to the AI-in-SDLC
spec (`docs/operations/ai-sdlc-integration-spec.md` §17 OP11, OP3, OP5).

## Bounded budget (OP11)

Every task and workflow **MUST** run under an explicit budget — **steps, time,
and/or cost** — and **MUST** terminate. There is no "run until done" with no
ceiling. The budget is part of a unit's delegation metadata ([[operating-model]],
spec §7.2) and **MUST** be recorded ([[decision-log]]).

The **8-iteration adversarial-review cap** ([[review-constitution]] iteration
protocol, spec §14.5) is one concrete instance of a bounded budget: the review
loop terminates at convergence **or** at 8 iterations, and an unconverged cap-hit
records residual risk and routes to gated approval rather than looping forever.
Other workflows (investigation passes, retry loops, multi-pass refinement) **MUST**
carry their own analogous ceilings.

## Non-progress / runaway-loop detection

The orchestrator **MUST** detect when work is **not advancing toward its success
criteria** — repeating the same state, re-deriving the same failing result, or
oscillating between states without converging — and **MUST** stop rather than
spend the rest of the budget. On detecting non-progress the orchestrator **MUST**:

- **stop** the looping task — do not keep iterating in hope;
- **escalate** to the user / a higher authority with what was attempted and why it
  is not progressing;
- **record** the non-progress and the stop decision ([[decision-log]]).

Budget exhaustion is itself a failure mode: a task that hits its step/time/cost
ceiling **without** meeting its success criteria **MUST** fail safe and escalate,
**MUST NOT** silently report success, and **MUST NOT** be reintegrated as if it
had converged ([[operating-model]] failure handling).

## Bounded retries — no masking persistent failure (OP3)

Retries **MUST** be bounded and **MUST NOT** mask a persistent failure. A task
that is **retried and still fails** **MUST** be escalated and recorded — it
**MUST NOT** be silently retried indefinitely, nor reported as passing, nor have
its failing gate worked around ([[control-integrity]]). The distinction matters:
retry is for **transient** failures (a flaky network call, a momentarily
unavailable model — see fallback in [[operating-model]]); a failure that survives
the retry budget is **persistent** and must surface. (Retrying a *side-effecting*
action carries the additional constraint in [[replay-safety]]: it must be
idempotent, guarded, or marked non-replayable first.)

## Cost-budget enforcement (OP5)

A task or workflow that **would exceed its cost budget MUST be deferred or
escalated, not silently run**. Cost is controlled along agent count, model class,
and execution duration, and bounded by the concurrency caps ([[operating-model]]
parallelism limits, spec §8). When projected spend would breach the budget, the
orchestrator **MUST** queue/defer the work or escalate for a budget decision, and
**MUST** record that it did so — it **MUST NOT** quietly run over budget.

## Relationship to the operator halt

This rule is the system's **own** liveness guard — automatic detection of
budget exhaustion, non-progress, and persistent failure. The **operator halt**
([[operator-halt]], spec §17 OP10) is the complementary **manual** stop: a human
can pause or halt all AI activity at any time. Long-running and autonomous loops
**MUST** check the operator halt between iterations as well as enforcing their own
budget — the two stops are independent and both apply.
