# Awesome-list PRs — MockServer

## TL;DR

MockServer is being added to the community "awesome" lists. **3 PRs are open**, **1 list already
lists MockServer**, and **1 was skipped as out of scope**.

| List | Outcome | Link |
|------|---------|------|
| `TheJambo/awesome-testing` | ✅ PR open | [#150](https://github.com/TheJambo/awesome-testing/pull/150) |
| `mfornos/awesome-microservices` | ✅ PR open | [#314](https://github.com/mfornos/awesome-microservices/pull/314) |
| `Kikobeats/awesome-api` | ✅ PR open | [#98](https://github.com/Kikobeats/awesome-api/pull/98) |
| `atinfo/awesome-test-automation` | ✔️ already listed | `java-test-automation.md` → "Useful libs" |
| `awesome-selfhosted/awesome-selfhosted` | ⏭️ skipped (out of scope) | see below |

All three PRs are OPEN and MERGEABLE; no CI checks have run against them yet. None of the three
runs `awesome-lint`, but TheJambo/awesome-testing runs a dead-link checker + Copilot review and
mfornos/awesome-microservices runs a link linter (the added URLs are live, so these should pass);
Kikobeats/awesome-api has no workflows. Each PR adds one bullet, in the correct section, matching
the list's house style. Raised from forks `jamesdbloom/awesome-testing`,
`jamesdbloom/awesome-microservices`, and `jamesdbloom/awesome-api`, each on branch `add-mockserver`.

---

## ✅ Target 1 — `TheJambo/awesome-testing` → PR #150

Section **`### Service Virtualization`** in `README.md`, inserted after `mockd` and before
`WireMock` (the section isn't strictly alphabetical, but this matches the existing ordering). Entry
(matches the section's GitHub-repo link style):

```markdown
- [MockServer](https://github.com/mock-server/mockserver-monorepo) - Open-source mock server and proxy for HTTP(S), REST, gRPC and LLM APIs with request verification, record/replay, and chaos injection; runs as a Docker image, JAR, Helm chart, or embedded Java library.
```

## ✅ Target 2 — `mfornos/awesome-microservices` → PR #314

Section **`### Testing`** in `README.md`, alphabetically between `Mitmproxy` and `Mountebank`.
Entry (homepage link, matching neighbours like Mountebank/WireMock):

```markdown
- [MockServer](https://www.mock-server.com) - Mock server and proxy for HTTP(S), REST, gRPC and LLM APIs with request verification, record/replay, and chaos injection for resilience testing.
```

## ✅ Target 3 — `Kikobeats/awesome-api` → PR #98

Section **`### Mocking`** in `README.md` (uses `*` bullets), placed among the mock-server tools
after `json-server`:

```markdown
* [MockServer](https://www.mock-server.com) - Mock any HTTP(S), REST, gRPC or LLM API; proxy, record/replay, verify requests, and inject chaos. Runs as a Docker image, JAR, or Helm chart.
```

## ✔️ Target 4 — `atinfo/awesome-test-automation` (already listed)

No PR needed — MockServer is **already present** in `java-test-automation.md` under "Useful libs":
`[MockServer](http://www.mock-server.com/) can be used for mocking any system you integrate with
via HTTP or HTTPS …`. Optional future tidy: refresh that description and the `http://` URL to
`https://`, but not worth a PR on its own.

## ⏭️ Target 5 — `awesome-selfhosted/awesome-selfhosted` (skipped — out of scope)

**Skipped.** Despite MockServer being Apache-2.0 and Docker/Helm self-hostable, awesome-selfhosted
curates **self-hosted end-user network services/applications** (the kind you host instead of a SaaS),
**not developer testing tools/libraries**. A mock-server/test tool falls outside the list's scope
and would very likely be closed. (This corrects the earlier draft's "it qualifies" note.) Also note
the list is now maintained as data in `awesome-selfhosted/awesome-selfhosted-data` (one YAML file per
entry), not by editing the README. Revisit only if their scope changes.

---

## Shared notes

- All PRs are to external repos under the maintainer's GitHub account (`jamesdbloom`); merging is up
  to each list's maintainer.
- Entries follow `awesome-lint` style (sentence-case, trailing period) where it fits the house style.
  Note Kikobeats/awesome-api uses `*` bullets (not `-`), so that entry matches `*` to fit the list.
- The bullets only claim channels that are live (Docker Hub, Helm OCI, multi-language clients).
- On merge each list regenerates its README automatically. If a PR is instead closed (e.g. the
  maintainer requests changes or deems it out of scope), record the reason and update the table above.
