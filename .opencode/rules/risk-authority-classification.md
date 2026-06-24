# Risk & Authority Classification

Every unit of work is classified by **risk**, and that class sets how
autonomously AI may act on it. Autonomy is **earned through verification
strength and track record, not assumed**. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §3.3, §19).

## Authority classes

| Class | AI may… | Gate before reintegration |
|-------|---------|---------------------------|
| **Act autonomously** | produce **and** reintegrate | the full [[commit-workflow]] gate chain returns PASS — no human pre-approval |
| **Gated approval** (spec: "act with gated approval") | produce, but not reintegrate alone | gate chain PASS **plus** explicit human / service-owner approval |
| **Advisory only** | propose; a human decides and owns the action | human decision |
| **Reserved** | **MUST NOT act** — the decision belongs **exclusively to a human authority**; explicit human direction is required before acting | — |

These class names are used consistently as `act-autonomously` / `gated-approval`
/ `advisory` / `reserved` throughout the rules.

## Risk dimensions (classify each unit on these)

Blast radius · reversibility · security sensitivity · compliance sensitivity ·
production impact · customer impact · ambiguity · verification coverage/strength ·
novelty · dependency complexity.

## Routing — risk to authority

- **Low risk + strong verification** (local code/docs/tests fully covered by the
  gate chain, reversible) → **MUST** route to **act-autonomously**.
- **Medium risk / weaker verification** (cross-module, partial coverage, novel
  pattern) → **act-autonomously only if the gate chain fully covers the change**;
  otherwise **MUST** route to **gated-approval**.
- **High risk / sensitive / irreversible** → **MUST** route to **advisory or
  reserved**.

### Always at least Gated approval (never autonomous), regardless of size

- Changes to the **controls themselves** — tests/gates, the review constitution,
  model/temperature/effort routing, guardrails, or this risk/authority policy. These are
  the *higher-scrutiny change class*; see [[control-integrity]]. **Separation of
  duties is mandatory**: the agent that authored the change MUST NOT be its
  approving reviewer — a distinct review agent or a human gates reintegration.
  When the change is to an **AI component** (prompt, constitution, routing,
  guardrail, model/version), it must additionally pass the evaluation harness
  before rollout.
- Production-affecting or irreversible actions: releases, production infra /
  Terraform `apply`, secrets/credentials, DNS, data deletion.
- Destructive git (`reset --hard`, `push --force`, history rewrite, `clean -fd`) —
  already requires explicit confirmation, see [[git-safety]].

### Reserved (AI must not act without explicit user direction)

- Irreversible external/publishing actions (e.g. publishing to a public registry,
  sending external communications).
- Policy changes to the authority classes or risk thresholds **themselves** —
  these are owned by the user / policy owner, not self-modifiable by an agent.

## Earned autonomy

A work type's autonomy level **MAY** be promoted only on a track record of
verified success, and **MUST** be demoted on evidence of failure. When
verification cannot reach sufficient confidence for the unit's class, **downgrade**
(autonomous → gated → advisory) and escalate rather than proceeding.

## Escalation triggers (spec §19.3)

Escalate to a human when **any** of these holds (spec §19.3):

- **Ambiguity materially blocks progress.**
- **A decision exceeds delegated authority** (the unit's authority class).
- **A policy boundary is reached.**
- **Verification cannot reach the confidence threshold.**
- **Adversarial review fails to converge within 8 iterations** (spec §14.5 — record residual risk, route to gated approval/escalation).
- **Subagents irreconcilably disagree** (reconcile via evidence/adversarial review first; escalate only if irreconcilable).
- **Cost or concurrency limits would be exceeded by the only viable path** (spec §8, §17 OP5 — defer or escalate, never silently exceed a cap).

Make the escalation structured (what is unclear · why it matters · recommended
option first · alternatives · impact). See [[operating-model]] (Clarify Well,
Rarely) and [[commit-workflow]].

## Escalation interaction model (spec §19.4) — autonomy-first

Escalation is the exception, not the loop. The interaction model is autonomy-first:

- **Prefer autonomy over interruption — escalations are non-blocking where safe.**
  Continue independent work and proceed on the **strongest safe assumption**
  (spec §5.2) rather than idling; escalate *synchronously* **only** when the
  decision gates correctness, safety, or authority.
- **Batch, don't drip.** Related questions and escalations are **consolidated into
  a single decision point**, not surfaced one at a time; suppress repeated
  low-value interruptions.
- **Decidable at a glance.** Each escalation is structured — what is unclear · why
  it matters · **recommended option first** · alternatives · impact/blast-radius —
  with enough attached context to decide without re-gathering it (spec §13).
- **Route by urgency.** Each escalation carries a **priority** so high-risk items
  surface promptly and low-risk ones do not bury them.
- **Never silently expire.** A pending escalation **MUST NOT** lapse into a default
  action; unresolved high-risk escalations keep the affected work in a safe,
  **non-reintegrated** state until decided (spec §20).

## Data governance (spec §16 S4)

Any data residency, retention, or governance constraints **MUST** be respected,
and sensitive data **MUST NOT** be used for model training or retained beyond
policy (spec §16 S4). **Position for this repository:** MockServer is a public
open-source repository with no customer or personal data in scope, so there is no
residency or retention obligation here — *n/a for this public OSS repo*. The
constraint still binds any work that touches genuinely sensitive context (e.g.
secrets, credentials, private infrastructure identifiers): such data **MUST NOT**
appear in artefacts, logs, traces, or decision logs (spec §16 S3), and **MUST NOT**
be sent for training or retained beyond what the task requires.
