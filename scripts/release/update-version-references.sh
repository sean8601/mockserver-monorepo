#!/usr/bin/env bash
# Update version references in docs/configs across the repo after a successful
# Maven Central publish. Commits and pushes the changes to master so that
# downstream parallel publish steps (website, helm, docker, etc.) read the
# new version when they clone the repo on their own agents.
#
# This used to live at the tail of finalize.sh. It was moved to its own stage
# and inserted before the parallel publish group because finalize runs LAST,
# so the website (and other deploys) were shipping the previous version's
# _config.yml / package.json / etc. (See incident: build #39 — release 6.1.0
# website published with mockserver_version=6.0.0.)
#
# Dry-run: do the version-reference updates locally so the diff can be
# reviewed; skip the git push.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd git
require_cmd python3
require_cmd jq
require_cmd sed
require_release_inputs
skip_unless_release_type "update-version-references" full,maven-only,post-maven

log_step "Update version references to $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

MAJOR="${RELEASE_VERSION%%.*}"
MINOR_REST="${RELEASE_VERSION#*.}"
MINOR="${MINOR_REST%%.*}"
API_VERSION="${MAJOR}.${MINOR}.x"

OLD_MAJOR="${OLD_VERSION%%.*}"
OLD_MINOR_REST="${OLD_VERSION#*.}"
OLD_MINOR="${OLD_MINOR_REST%%.*}"
OLD_API_VERSION="${OLD_MAJOR}.${OLD_MINOR}.x"

escape_sed() { printf '%s' "$1" | sed -e 's/[][\\/.^$*]/\\&/g'; }
sed_i() {
  if sed --version 2>/dev/null | grep -q GNU; then sed -i "$@"; else sed -i '' "$@"; fi
}

# Release-time guard (fail-closed): after the bump, prove EVERY testcontainers
# client carries the new RELEASE_VERSION as its default image, so a stale default
# (the 7.2.0 incident) — or a NEW client that nobody wired into the bump list
# above — can never silently ship. Two checks:
#   (a) every "mockserver-X.Y.Z" image tag anywhere under mockserver-testcontainers/
#       equals "mockserver-$RELEASE_VERSION";
#   (b) the three bare version constants (__version__ / DefaultVersion /
#       MOCKSERVER_VERSION) each equal "$RELEASE_VERSION".
# Lines tagged "mockserver-version-guard:ignore" are intentional non-default
# examples (e.g. the rust custom_tag test) and are skipped. Build artifacts and
# lockfiles are excluded. On ANY mismatch we list every offender file:line and
# exit 1. Run ONLY outside dry-run: in dry-run the constants are deliberately
# left un-bumped, so this would always (correctly) fail there.
assert_testcontainers_versions() {
  local tc_dir="$REPO_ROOT/mockserver-testcontainers"
  [[ -d "$tc_dir" ]] || { log_info "No mockserver-testcontainers/ dir — skipping default-image guard"; return 0; }

  local expected_tag="mockserver-$RELEASE_VERSION"
  local failures=0

  # Shared exclusion set for both scans: prune build/vcs dirs, and post-filter
  # out lockfiles (*.lock, package-lock.json) — they can carry the OLD version
  # in a resolved-dependency entry and are never the place a default image lives.
  local -a prune=(
    -name node_modules -o -name target -o -name dist -o -name build -o -name .git
  )
  local lock_re='(/package-lock\.json|\.lock):'

  # (a) Image-tag pattern. grep -n gives file-relative line numbers; we prefix the
  # path. Anchor each match's full X.Y.Z so a tag that is NOT the release version
  # is reported. Skip guard-ignored lines.
  while IFS= read -r match; do
    [[ -z "$match" ]] && continue
    # match is path:lineno:content
    case "$match" in *mockserver-version-guard:ignore*) continue ;; esac
    # Extract every mockserver-X.Y.Z on the line and verify each equals the release tag.
    local tok
    while IFS= read -r tok; do
      [[ -z "$tok" ]] && continue
      if [[ "$tok" != "$expected_tag" ]]; then
        log_error "stale testcontainers default image: $match (found $tok, expected $expected_tag)"
        failures=$((failures + 1))
      fi
    done < <(grep -oE 'mockserver-[0-9]+\.[0-9]+\.[0-9]+' <<<"$match")
  done < <(find "$tc_dir" \( "${prune[@]}" \) -prune -o -type f -print0 2>/dev/null \
    | xargs -0 grep -nE 'mockserver-[0-9]+\.[0-9]+\.[0-9]+' 2>/dev/null \
    | sed "s#^$REPO_ROOT/##" \
    | grep -vE "$lock_re" || true)

  # (b) Bare constants — exact match required for each named identifier.
  local id
  for id in '__version__' 'DefaultVersion' 'MOCKSERVER_VERSION'; do
    while IFS= read -r match; do
      [[ -z "$match" ]] && continue
      case "$match" in *mockserver-version-guard:ignore*) continue ;; esac
      # Pull the X.Y.Z that this identifier is assigned. The assignment forms are
      #   __version__ = "X.Y.Z"   DefaultVersion = "X.Y.Z";   MOCKSERVER_VERSION: &str = "X.Y.Z";
      local found
      found=$(grep -oE "$id[^\"']*[\"'][0-9]+\.[0-9]+\.[0-9]+" <<<"$match" | grep -oE '[0-9]+\.[0-9]+\.[0-9]+$' || true)
      [[ -z "$found" ]] && continue
      if [[ "$found" != "$RELEASE_VERSION" ]]; then
        log_error "stale testcontainers constant $id: $match (found $found, expected $RELEASE_VERSION)"
        failures=$((failures + 1))
      fi
    done < <(find "$tc_dir" \( "${prune[@]}" \) -prune -o -type f -print0 2>/dev/null \
      | xargs -0 grep -nE "(^|[^A-Za-z0-9_])$id[[:space:]:=]" 2>/dev/null \
      | sed "s#^$REPO_ROOT/##" \
      | grep -vE "$lock_re" || true)
  done

  if (( failures > 0 )); then
    log_error "assert_testcontainers_versions: $failures testcontainers default-image version(s) are NOT $RELEASE_VERSION."
    log_error "A testcontainers client default image is stale, OR a new client needs wiring into the TESTCONTAINERS_VERSION_FILES bump block in $(basename "$0")."
    exit 1
  fi
  log_info "assert_testcontainers_versions: all testcontainers client default images = $RELEASE_VERSION"
}

if is_dry_run; then
  log_dry "would: rewrite version refs across changelog, jekyll config, packages, docs, and testcontainers client default-image constants"
else
  # Roll the changelog: open a fresh empty [Unreleased] and stamp a dated
  # [RELEASE_VERSION] section from the previous Unreleased content.
  #
  # IDEMPOTENT: only roll if a [RELEASE_VERSION] section does not already exist.
  # This step runs on full, maven-only AND post-maven, and the sed below matches
  # the always-present "## [Unreleased]" header — so without this guard a retried
  # full run or a post-maven re-run inserts ANOTHER "## [RELEASE_VERSION]" block
  # below the re-created Unreleased header every single time it runs. That is the
  # bug that duplicated the 7.1.0 section across its repeated re-runs (its many
  # "update version references to 7.1.0" commits), later cleaned up by hand in
  # commit af8d1a3e5 "removed duplicate entries from the changelog.md".
  RELEASE_VERSION_RE=$(printf '%s' "$RELEASE_VERSION" | sed 's/\./\\./g')
  if grep -qE "^## \[$RELEASE_VERSION_RE\]" "$REPO_ROOT/changelog.md"; then
    log_info "changelog.md already has a [$RELEASE_VERSION] section — skipping changelog rollover (idempotent re-run)"
  else
    TODAY=$(date +%Y-%m-%d)
    sed_i "s/^## \[Unreleased\]/## [Unreleased]\n\n### Added\n\n### Changed\n\n### Fixed\n\n## [$RELEASE_VERSION] - $TODAY/" "$REPO_ROOT/changelog.md"
  fi

  JEKYLL_CONFIG="$REPO_ROOT/jekyll-www.mock-server.com/_config.yml"
  sed_i "s/^mockserver_version: .*/mockserver_version: $RELEASE_VERSION/" "$JEKYLL_CONFIG"
  sed_i "s/^mockserver_api_version: .*/mockserver_api_version: $API_VERSION/" "$JEKYLL_CONFIG"
  sed_i "s/^mockserver_snapshot_version: .*/mockserver_snapshot_version: $NEXT_VERSION/" "$JEKYLL_CONFIG"

  # OpenAPI spec version is bumped by prepare.sh (audit F-SH-01) so that
  # swaggerhub.sh in the parallel publish group uploads a spec whose body
  # matches its registry version label. No bump needed here.

  for PKG_DIR in mockserver-node mockserver-client-node; do
    PKG_FILE="$REPO_ROOT/$PKG_DIR/package.json"
    if [[ -f "$PKG_FILE" ]]; then
      TMP="$REPO_ROOT/.tmp/pkg-$PKG_DIR.json"
      mkdir -p "$REPO_ROOT/.tmp"
      jq --arg v "$RELEASE_VERSION" '.version = $v' "$PKG_FILE" > "$TMP" && mv "$TMP" "$PKG_FILE"
    fi
  done
  if [[ -f "$REPO_ROOT/mockserver-node/package.json" ]]; then
    sed_i "s/$(escape_sed "mockserver-netty-${OLD_VERSION}-jar-with-dependencies.jar")/mockserver-netty-${RELEASE_VERSION}-jar-with-dependencies.jar/g" \
      "$REPO_ROOT/mockserver-node/package.json"
  fi
  if [[ -f "$REPO_ROOT/mockserver-client-node/package.json" ]]; then
    TMP="$REPO_ROOT/.tmp/pkg-client-node.json"
    jq --arg v "$RELEASE_VERSION" '.devDependencies["mockserver-node"] = $v' \
      "$REPO_ROOT/mockserver-client-node/package.json" > "$TMP" \
      && mv "$TMP" "$REPO_ROOT/mockserver-client-node/package.json"
  fi

  PYPROJECT="$REPO_ROOT/mockserver-client-python/pyproject.toml"
  [[ -f "$PYPROJECT" ]] && sed_i "s/^version = \".*\"/version = \"$RELEASE_VERSION\"/" "$PYPROJECT"

  VERSION_RB="$REPO_ROOT/mockserver-client-ruby/lib/mockserver/version.rb"
  [[ -f "$VERSION_RB" ]] && sed_i "s/VERSION = '.*'/VERSION = '$RELEASE_VERSION'/" "$VERSION_RB"
  RUBY_README="$REPO_ROOT/mockserver-client-ruby/README.md"
  [[ -f "$RUBY_README" ]] && sed_i "s/$OLD_VERSION/$RELEASE_VERSION/g" "$RUBY_README"

  # Testcontainers client default-image version constants live in SOURCE files
  # (.go/.ts/.py/.rs/.cs), not in the *.html/*.md/*.yaml/*.json/*.txt set the
  # blanket find loop below sweeps — so without this explicit bump the READMEs
  # got the new tag but the compiled-in DEFAULT image stayed stale. That is
  # exactly what shipped a 7.2.0 client defaulting to the previous image until
  # it was hand-fixed; this block + the assert_testcontainers_versions guard
  # below make it impossible to ship a stale default again. Each client's
  # constant and its test assertion are listed together so they bump in lock-step
  # (a half-bump would otherwise red the client's own test, not the release).
  #
  # These files contain no third-party semver tokens, so the SAME anchored
  # replacement the general loop uses is safe here — do NOT add these source
  # extensions to the blanket find loop (it would sweep node_modules/target).
  # The rust custom_tag test literal "mockserver-5.15.0" is intentionally a
  # non-default example; the anchored OLD_VERSION->NEW sed never touches it
  # (OLD_VERSION != 5.15.0) and the guard skips its tagged lines.
  OLD_PAT=$(escape_sed "$OLD_VERSION"); NEW_REP=$(escape_sed "$RELEASE_VERSION")
  # Skip the substitution on lines that describe a HISTORICAL milestone
  # (e.g. "Fixed in 6.0.x", "Before 6.0.0", "removed in 6.0.0", "switched
  # to X in 6.0.0", "Since 5.15.0"). On those lines the version is a
  # milestone marker, not a current-version reference; bumping it to the
  # new release would falsify the statement. Trade-off: a line that mixes
  # a historical milestone AND a current-version reference would be
  # protected too, but that pattern is rare enough to be a tolerable false
  # negative — the operator can always fix it by hand in the finalize
  # commit. The list is intentionally broad (covers "Fixed in", "removed
  # in", "switched ... in", etc.) because this step runs unattended and we
  # prefer false negatives (missed bump) over false positives (mangled
  # documentation lie). Defined here (before the testcontainers bump) because
  # both the explicit source-file bump and the general doc loop use it.
  HISTORICAL_RE='([Bb]efore|[Uu]ntil|[Ss]ince|[Ff]ixed in|[Rr]emoved in|[Ii]ntroduced in|[Dd]eprecated in|[Aa]dded in|[Uu]pdated in|[Rr]eleased in|[Cc]hanged in|[Aa]s of|[Rr]equires|[Mm]inimum version[:]?|switched [^.]+ in|moved [^.]+ in|migrated [^.]+ in|renamed [^.]+ in|published [^.]+ in)[[:space:]]+[0-9]+\.[0-9]+'
  declare -a TESTCONTAINERS_VERSION_FILES=(
    mockserver-testcontainers/go/mockserver.go
    mockserver-testcontainers/go/doc.go
    mockserver-testcontainers/go/mockserver_unit_test.go
    mockserver-testcontainers/node/src/mockserver-container.ts
    mockserver-testcontainers/python/src/testcontainers_mockserver/__init__.py
    mockserver-testcontainers/python/src/testcontainers_mockserver/container.py
    mockserver-testcontainers/python/tests/test_container_config.py
    mockserver-testcontainers/rust/src/lib.rs
    mockserver-testcontainers/dotnet/src/Testcontainers.MockServer/MockServerContainer.cs
    mockserver-testcontainers/dotnet/tests/Testcontainers.MockServer.Tests/MockServerBuilderTest.cs
  )
  for rel in "${TESTCONTAINERS_VERSION_FILES[@]}"; do
    file="$REPO_ROOT/$rel"
    [[ -f "$file" ]] || continue
    sed_i -E "/${HISTORICAL_RE}/!s/(^|[^0-9.])${OLD_PAT}([^0-9]|\$)/\1${NEW_REP}\2/g" "$file"
  done

  # General find-and-replace across docs (excluding changelog, target, etc.)
  # OLD_PAT/NEW_REP and HISTORICAL_RE are defined above (shared with the
  # testcontainers source-file bump).
  OLD_API_PAT=$(escape_sed "$OLD_API_VERSION"); NEW_API=$(escape_sed "$API_VERSION")
  # mockserver-{node,client-node}/package.json are excluded from the general
  # find-and-replace because their version references are bumped explicitly
  # with jq above (precise field targeting). Without this guard the blanket
  # OLD_VERSION->NEW_VERSION sed would rewrite third-party prerelease tags
  # that share the OLD_VERSION prefix (e.g. grunt-ts@^6.0.0-beta.22). Example
  # package.json files (under */examples/) are *not* excluded — they carry
  # first-party "mockserver-client"/"mockserver-node" deps that must bump.
  for ext in "*.html" "*.md" "*.yaml" "*.yml" "*.json" "*.txt"; do
    find "$REPO_ROOT" -name "$ext" \
      -not -path "*/node_modules/*" -not -path "*/.git/*" \
      -not -path "*/target/*" -not -path "*/helm/charts/*" \
      -not -path "*/.tmp/*" \
      -not -name "changelog.md" -not -name "CHANGELOG.md" \
      -not -path "*/mockserver-node/package.json" \
      -not -path "*/mockserver-client-node/package.json" \
      -not -path "*/mockserver-ui/package.json" \
      -not -name "package-lock.json" -print0 2>/dev/null \
    | while IFS= read -r -d '' file; do
        # Anchor the version so it only matches a COMPLETE version token, never a
        # substring of a larger version. Without the boundaries, OLD_VERSION 6.1.0
        # matched inside a third-party dependency range like "^16.1.0" and bumped
        # it to the non-existent "^17.0.0", breaking `npm ci` for mockserver-ui
        # (and thus every mvn build of mockserver-netty + CodeQL). The leading
        # group requires a non-digit/non-dot before the version; the trailing
        # group a non-digit after it.
        sed_i -E "/${HISTORICAL_RE}/!s/(^|[^0-9.])${OLD_PAT}([^0-9]|\$)/\1${NEW_REP}\2/g" "$file" 2>/dev/null || true
        if [[ "$OLD_API_VERSION" != "$API_VERSION" ]]; then
          sed_i -E "/${HISTORICAL_RE}/!s/(^|[^0-9.])${OLD_API_PAT}([^0-9]|\$)/\1${NEW_API}\2/g" "$file" 2>/dev/null || true
        fi
      done
  done

  # Fail-closed release-time guard: validate the bump took on EVERY testcontainers
  # client (and catch an unwired/new client) BEFORE committing. Only meaningful
  # outside dry-run, where the bump actually ran.
  assert_testcontainers_versions
fi

log_info "Diff summary:"
# --no-pager: Buildkite allocates a PTY, so git's stdout is a TTY and a bare
# `git diff --stat` launches the pager (less), which blocks on input until the
# job hits its timeout. This is what timed out the Update Version References
# step in release build #49.
git -C "$REPO_ROOT" --no-pager diff --stat

if is_dry_run; then
  log_dry "skip: commit + push of version references"
else
  # Explicit path list — never `.` — so untracked files on the agent (build
  # artifacts, .tmp/ files, anything left behind by an earlier step) don't
  # get committed by accident.
  declare -a UPDATED_PATHS=(
    changelog.md
    jekyll-www.mock-server.com/_config.yml
  )
  # NOTE: the OpenAPI spec used to be listed here, but prepare.sh now bumps
  # it as part of the non-Maven manifest pass and commits it in the prepare
  # commit (audit F-SH-01) so swaggerhub.sh uploads a spec with the correct
  # internal version. Don't add it back here.
  [[ -f mockserver-node/package.json ]]            && UPDATED_PATHS+=(mockserver-node/package.json)
  [[ -f mockserver-client-node/package.json ]]     && UPDATED_PATHS+=(mockserver-client-node/package.json)
  [[ -f mockserver-client-python/pyproject.toml ]] && UPDATED_PATHS+=(mockserver-client-python/pyproject.toml)
  [[ -f mockserver-client-ruby/lib/mockserver/version.rb ]] && UPDATED_PATHS+=(mockserver-client-ruby/lib/mockserver/version.rb)
  [[ -f mockserver-client-ruby/README.md ]]        && UPDATED_PATHS+=(mockserver-client-ruby/README.md)
  # Testcontainers client default-image source/test files bumped above. The .ts
  # is also caught by the general doc loop's *.ts? no — that loop only sweeps
  # *.html/*.md/*.yaml/*.yml/*.json/*.txt, so the .go/.ts/.py/.rs/.cs SOURCE files
  # are NOT staged by it and must be listed explicitly here.
  for rel in "${TESTCONTAINERS_VERSION_FILES[@]}"; do
    [[ -f "$rel" ]] && UPDATED_PATHS+=("$rel")
  done
  # General find-and-replace touched docs across the repo. Stage only the
  # files that the find/replace loop above actually edited — NEVER a catch-all
  # `git diff --name-only` which would stage unrelated pre-existing changes
  # from an earlier step.
  # Re-run the same find as above and collect only files that have a diff.
  for ext in "*.html" "*.md" "*.yaml" "*.yml" "*.json" "*.txt"; do
    while IFS= read -r -d '' file; do
      # Relativize to repo root for git staging.
      local_path="${file#"$REPO_ROOT/"}"
      if ! git -C "$REPO_ROOT" diff --quiet -- "$local_path" 2>/dev/null; then
        UPDATED_PATHS+=("$local_path")
      fi
    done < <(find "$REPO_ROOT" -name "$ext" \
      -not -path "*/node_modules/*" -not -path "*/.git/*" \
      -not -path "*/target/*" -not -path "*/helm/charts/*" \
      -not -path "*/.tmp/*" \
      -not -name "changelog.md" -not -name "CHANGELOG.md" \
      -not -path "*/mockserver-node/package.json" \
      -not -path "*/mockserver-client-node/package.json" \
      -not -path "*/mockserver-ui/package.json" \
      -not -name "package-lock.json" -print0 2>/dev/null)
  done
  # De-duplicate.
  mapfile -t UPDATED_PATHS < <(printf '%s\n' "${UPDATED_PATHS[@]}" | sort -u)
  git_commit_and_push "release: update version references to $RELEASE_VERSION" "${UPDATED_PATHS[@]}"
fi

log_info "Update version references complete"
