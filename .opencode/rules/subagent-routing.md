# Subagent Routing

Routing policy must live in routing configuration, not in skill descriptions.

## Delegate Almost Everything (The Orchestrator Does Little Directly)

The main agent's primary job is to **orchestrate subagents**. It should delegate
the **overwhelming majority of execution — implementation *and* investigation —
to subagents**, keeping its own context for planning, decomposition, routing,
review, and escalation (see [[operating-model]], spec §5.5/§7).

The reason is not only context preservation. **A subagent is where the correct
model, temperature, and reasoning effort are selected for the task** — and that
per-task selection is the primary lever for managing **inference cost** and
**output determinism**. Investigation counts: a read-only "why is X happening"
question routed to a right-sized subagent (e.g. `debugger`,
`pipeline-investigator`) keeps a hard problem on a strong model at high effort
while a mechanical lookup runs cheap, instead of paying the orchestrator's
configuration for everything.

Do work **inline only for the trivial residue** where spinning a subagent would
add no value (a one-line edit, a single-file read to answer a quick question).
When you do delegate, pick the subagent whose configured model/temperature/effort
fits the task's risk and reasoning depth — see the routing table in `AGENTS.md`,
the conversational table below, and the per-agent configuration in
`opencode.jsonc` / `.claude/agents/`.

## Task classification — the seven dimensions (spec §9.1)

Before selecting a subagent profile (model + temperature + reasoning effort +
least-privilege tool/permission scope), classify the task on **seven dimensions**
— these are the explicit basis for the selection (spec §9.1, §9.2; [[risk-authority-classification]]):

1. **Complexity** — how much work and how many moving parts.
2. **Ambiguity** — how under-specified the task is (drives clarification vs assumption).
3. **Risk** — blast radius / reversibility / sensitivity (drives the authority class, [[risk-authority-classification]]).
4. **Required reasoning depth** — drives **reasoning effort** (high-effort for hard implementation, authoritative review, security audit, complex investigation; low for mechanical/shallow tasks).
5. **Required determinism** — drives **low temperature** / stronger model where determinism matters; on harnesses without a temperature knob, effort and model choice are the determinism lever in its place.
6. **Required creativity** — may justify **higher temperature** for exploratory/ideation work.
7. **Available verification strength** — how well the gate chain can catch defects (feeds both the authority class and how much to invest in review).

Pick the subagent whose configured profile fits the classification; prefer the
cheapest model/temperature/effort that meets the quality threshold (spec O8, C13).
The classification → profile routing **MUST** be recorded in the decision log
(spec §9.2, §21) — see *Delegation metadata* below.

## Delegation metadata (spec §7.2)

Every delegated task **MUST** carry **delegation metadata**, and these decisions
**MUST** be recorded (decision log, spec §21):

- **Scope and boundaries** — what is in and out of scope for the unit.
- **Explicit success criteria** — what "done" means, verifiably.
- **Required context references** — the code, docs, ADRs, interfaces, and history the task needs (spec §10).
- **Dependencies** — upstream/downstream units and their sequencing.
- **Selected model class, temperature, reasoning effort, and least-privilege tool/permission scope** — each **with its rationale** (spec §9, §16 S1/S12). Scope is the *least* capability the task requires.
- **Risk class** — the authority class from [[risk-authority-classification]] (act-autonomously / gated-approval / advisory / reserved).
- **Verification expectations** — which gates apply and what evidence is required (spec §12).
- **Concurrency / isolation requirements** — worktree/session isolation and the unit's place under the parallelism caps (spec §8).

A subagent **type** may be defined as a fixed named (model, temperature, effort,
permission-scope) profile and tasks routed to it by classification; this is
conformant provided the classification → profile routing is recorded (spec §5.6, §9.2).

## Re-routing and fallback (spec §9.3, §17 OP2)

- A task **MAY** be **re-run with a different model / temperature / reasoning
  effort** if evidence shows the original configuration was unfit; the re-route
  **and its trigger MUST be recorded** (spec §9.3, §21).
- **Fallback behaviour MUST exist** for a selected model that
  **underperforms, fails, or is unavailable** — degrade to an alternative model
  class, escalate, or defer (spec §9.3, §20; [[risk-authority-classification]] §19.3).
- A **fallback MUST also exist when verification or diagnosis tooling is
  unavailable** (spec §17 OP2): degrade gracefully, escalate, or defer rather
  than claiming an unverifiable completion (spec §12 V6).

## Core Rule

- `SKILL.md` files describe what a skill does and how it executes.
- Routing decisions (which agent runs a request) are defined by:
  - command files in `.opencode/commands/` (`agent:` + `subtask: true`),
  - this routing table for conversational requests,
  - permission policy in `opencode.jsonc`.

Do not add caller-only routing directives like:

`MUST be launched as a Task subagent with subagent_type "<type>"`

inside skill descriptions. These directives can cause subagents to attempt self-dispatch.

## Conversational Routing Table

When users ask conversationally (not via slash commands), route as follows:

| Skill | Subagent Type | Typical Requests |
|-------|---------------|------------------|
| `pipeline-investigation` | `pipeline-investigator` | "investigate this build", "why is CI failing" |
| `aws-investigation` | `debugger` | "check build agents", "debug ASG scaling" |

## Concurrency Cap

When fanning out conversational or command-routed work to subagents, respect the
hard caps in [[operating-model]] (Parallelism Limits): **≤10 active subagents and
≤10-way parallelism at any one time**, with a lower effective limit when
warranted (e.g. complexity, cost, contention, model availability — see
[[operating-model]] for the full list). Queue or defer excess work rather than
exceed a cap.

## Implementation Notes

- Slash commands remain authoritative for deterministic routing.
- Conversational routing should use this table when skill descriptions are hidden by permissions.
- If a new subagent-routed skill is added, update:
  1. `.opencode/commands/<skill>.md`
  2. this file's conversational routing table
  3. `scripts/validate_opencode_config.sh` validation assumptions
