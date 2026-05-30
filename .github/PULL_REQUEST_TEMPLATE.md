<!--
Thanks for contributing to MockServer! Please read CONTRIBUTING.md first:
https://github.com/mock-server/mockserver-monorepo/blob/master/CONTRIBUTING.md
Keep this PR focused on a single logical change.
-->

## What & why

<!-- What does this change do, and why? Link any related issue, e.g. "Fixes #1234". -->

Fixes #

## Where

<!--
Which module(s) does this touch? See the "Where to make changes" table in
CONTRIBUTING.md to confirm the change is at the right architectural layer.
-->

- [ ] `mockserver/mockserver-core`
- [ ] `mockserver/mockserver-netty`
- [ ] Client library (Java / Node / Python / Ruby)
- [ ] Dashboard UI (`mockserver-ui`)
- [ ] Build / CI / infrastructure
- [ ] Documentation only
- [ ] Other (describe above)

## Checklist

- [ ] I read [CONTRIBUTING.md](../CONTRIBUTING.md) and (for non-trivial changes) opened an issue first to discuss the approach.
- [ ] The change is made at the architecturally correct layer (root cause, not the symptom).
- [ ] Tests added or updated for the new behaviour, and the relevant module's tests pass locally.
- [ ] User-facing changes have a `changelog.md` entry under `## [Unreleased]` (prefixed `BREAKING:` if applicable).
- [ ] User-facing changes update the consumer docs (`jekyll-www.mock-server.com/`) and/or internal docs (`docs/`) where relevant.
- [ ] Code targets **Java 17** (no APIs newer than Java 17; `jakarta.*` namespace where applicable).
- [ ] No secrets, credentials, or private endpoints are included in the diff.

## Notes for reviewers

<!-- Anything that helps review: trade-offs considered, areas you're unsure about, manual testing done. -->
