# Decision Log & Reproducibility

Significant AI work must be **reconstructable**: another agent or human should be
able to see what inputs were used, what was attempted, what evidence was observed,
and why a conclusion was reached. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §15, §21).

## What counts as "significant"

Apply to substantial / risky units (the full-DVRR path) and to any control change
(see [[control-integrity]]), production-affecting change, or escalation. Trivial
edits (typo, one-liner) need only their commit message. Don't over-record routine
micro-steps.

## What to record

For a significant unit, the following **MUST** be recorded:

- **why** the task was attempted (the goal / trigger);
- **context & inputs** used (key files, specs, issues — with provenance/trust where it matters, see [[untrusted-input]]);
- **assumptions** made (especially those used to proceed without clarifying, see [[operating-model]] "Clarify Well, Rarely");
- **model, temperature & reasoning effort** chosen and **why**, and whether a multi-pass strategy was used (§21.1);
- **tool/permission scope** granted and **why** — the least-capability set the task required (§9.2, §16 S12) (§21.1);
- **verification evidence** observed (which gates ran, pass/fail, key output);
- **review**: which review constitution(s) (and version) **and** which review tier(s) (unit / group / wave / plan) were applied (§14.6); the adversarial findings raised across iterations; and, for each CRITICAL / MAJOR finding, **how it was fixed** (not merely noted) — for MINOR findings, any recorded **deferral rationale** (§21.1 / §14);
- **outcome & why**: accepted / rejected / escalated / retried / re-routed;
- **intervention**: whether (and what) human intervention occurred — none / advisory steer / gated approval / correction / halt — so the §18.3 "% work without human intervention" metric has a source (§18.3);
- **discarded approaches** and why they were abandoned (especially for parallel / speculative work);
- **security-relevant events**: suspected injection, control-integrity flags, sandbox denials, operator halts ([[untrusted-input]], [[control-integrity]]).

Throughout, **"disposition"** is reserved for MINOR findings and for false-positive determinations — a CRITICAL or MAJOR finding is never merely "dispositioned"; it is **fixed** (or the unit does not ship).

Never put secrets into the log.

## Where it lives

Two complementary homes — use both, scaled to the unit:

- **The commit message** is the durable record of *why* and *what* (one coherent
  unit → one commit). This is the minimum for every committed unit.
- **`.tmp/decisions/<id>.md`** (gitignored, like the rest of `.tmp/`) holds the
  fuller trail — model/temperature/effort rationale, tool/permission scope, assumptions,
  per-iteration review findings (with the fix for each CRITICAL/MAJOR finding and the
  disposition of MINOR ones), the intervention field, and evidence — for significant or escalated units, and
  for any unit whose reasoning a later session may need to replay. `<id>` is a
  short task slug, timestamp-prefixed to avoid collisions between parallel
  sessions (e.g. `20260615-add-mcp-tool-registry.md`). Link it from the plan doc
  (`docs/plans/*.local.md`) when one exists. **When the work lives in a worktree**
  (which is removed after merge), copy the decision file into the primary
  checkout's `.tmp/decisions/` before cleanup — or fold the key reasoning into the
  commit message body — otherwise the trail is deleted with the worktree.

This extends the lightweight `.tmp/agent-activity` convention (a one-line "what
I'm doing now" for `/agent-status`): agent-activity is the live ticker; the
decision log is the durable trail.

## Reproducibility

LLM output is not bit-reproducible, so the **decision trace is the reproducibility
guarantee**: record model, temperature, reasoning effort, pinned inputs/context, and the reasoning
so a later agent or human can re-evaluate, challenge, or repeat the work — even if
the exact output differs.

The fields recorded here are also the primary data source for the AI-integration
metrics ([[metrics]]) — in particular the **intervention** field feeds the §18.3
"% work without human intervention" metric, and the review **tier** + **per-finding
fix** fields feed review convergence.
