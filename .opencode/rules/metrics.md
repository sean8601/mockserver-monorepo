# AI Integration Metrics

How the AI-in-SDLC integration is measured over time, so autonomy is expanded (or
pulled back) on evidence. Conforms to the AI-in-SDLC spec
(`docs/operations/ai-sdlc-integration-spec.md` §18).

## Discipline

- Capture a **baseline** at adoption where possible, so improvement can be shown.
- Collect on a defined **cadence**; each metric has a named **owner** who
  interprets it and acts.
- **Cadence:** review monthly, and after any material AI-component change
  ([[evaluation-harness]]).
- **Owner:** the AI-in-SDLC platform owner (see the spec's document-control
  header); the owner may delegate per-metric interpretation.

## Metrics

| Metric | Measure | Source | Target | Gate? |
|--------|---------|--------|--------|-------|
| Autonomy rate | % units completed without human intervention | decision-log **intervention** field ([[decision-log]]) / commit attribution | trend up † | no |
| Verification pass rate | % units whose gate chain passed first time | gate-chain results | high † | no |
| Rework rate | % units needing a follow-up fix/revert within 14 days (by convention) | git history (same files) | trend down † | no |
| Defect-escape rate | incidents/bugs traced to an AI-made change | incident/issue records | trend down † | no |
| Review convergence | mean review iterations to PASS; % hitting the 8-cap with residual risk | decision-log **review** field — tier(s), per-iteration findings, per-finding fix ([[decision-log]]) | low mean; ~0% cap-hits † | no |
| Model-routing fitness | % tasks correctly routed by model class (judged retrospectively) | decision-log **model** field + eval | high † | no |
| Temperature/effort-routing fitness | % tasks correctly routed by temperature **and reasoning effort** (judged retrospectively) | decision-log **temperature & effort** fields + eval | high † | no |
| Concurrency | avg & peak concurrent subagents | orchestration records / concurrency recorder (see below) | **≤10** (hard cap) | yes |
| Cost per unit | inference cost per completed unit | cost/usage telemetry (to instrument) | stable or down † | no |
| Trace completeness | % significant units with a sufficient decision trace | decision-log presence | high † | no |
| Consistency drift | config/convention drift incidents | `scripts/validate_opencode_config.sh` + lint | zero | yes |

This table focuses on the §18.3 MUST process metrics plus the most actionable
§18.2 outcomes; the full outcome inventory (cycle time, throughput, MTTD, quality
signal density, documentation quality) is in spec §18.2. **†** marks directional
placeholders — concrete numeric targets are an open decision (see Thresholds).

## Data source

The **decision log** ([[decision-log]]) is the primary data source — its fields
(model, temperature, **reasoning effort**, **tool/permission scope**, review
tier(s)/findings/iterations and per-finding fix, outcome, **intervention**,
security events) are exactly what most of these metrics aggregate. The evaluation harness
([[evaluation-harness]]) supplies routing-fitness evidence; the config validator
supplies drift.

### Derivable now vs needs light instrumentation

- **Now**, from git + decision logs + eval + validator: rework rate, review
  convergence, concurrency (observed against the cap), consistency drift,
  trace completeness.
- **Needs light instrumentation** (capture the fields in the decision log so they
  can be aggregated): autonomy rate, cost per unit, routing fitness.

### Defined vs collected (§18.3)

A metric is **defined** once it appears in the table above with a source; it is
**collected** only once something actually reads that source on the cadence and
produces a number. The §18.3 "MUST collect" set is operationalised as follows —
keep collection real even where it is a simple script or a manual cadence:

- **Concurrent-subagent recorder (avg & peak).** The orchestrator records each
  subagent spawn/exit (or samples the active count) so the cadence run can report
  **average and peak concurrent subagent count** against the ≤10 cap (§18.3, §8.1).
  A one-line-per-event jot in `.tmp/agent-activity` / the orchestration record is
  sufficient; the aggregation step (below) reduces it to avg/peak.
- **Decision-log aggregation step.** A lightweight aggregator reads the
  `.tmp/decisions/<id>.md` entries (and commit attribution) into the defined
  metrics on the cadence — at minimum: the **intervention** field → autonomy rate
  (§18.3); the **review** field (tier(s), iterations, per-finding fix) → review
  convergence rate / mean iterations / % hitting the 8-cap (§18.3, §14.5); the
  **model / temperature / effort** fields → "% routed correctly by model /
  temperature / effort" (§18.3). This MAY be a small script over
  `.tmp/decisions/` or a manual monthly tally, but it MUST run so the numbers
  exist rather than merely being derivable in principle. Until the script exists,
  the owner performs the tally by hand on the cadence; "defined" is not "collected".

## Thresholds

Concrete numeric targets (beyond the hard ≤10 concurrency cap and zero-drift) are
an open decision (spec §22.6). Until set, trend each metric against its baseline
and act on adverse trends — feeding findings into autonomy promotion/demotion
([[risk-authority-classification]]) and into the §21.5 feedback & learning loop
(the "Feedback & learning loop" section of [[operating-model]]), which reviews
model/temperature/effort-routing effectiveness, inference-cost trends, and
verification gaps, and prevents recurrence of known failure patterns (§21.5).
