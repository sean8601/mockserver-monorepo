#!/usr/bin/env bash
# Publish mockserver-client-php to Packagist via a dedicated split-repo mirror.
#
# Packagist requires composer.json at the REPOSITORY ROOT of the default branch
# and does NOT support subdirectory packages. Because the PHP client lives at
# mockserver-client-php/ inside this monorepo, it is published through a
# read-only mirror repo whose root IS the package:
#
#   github.com/mock-server/mockserver-client-php   (master = subtree split of
#                                                    mockserver-client-php/)
#
# The monorepo stays the single source of truth — this step regenerates the
# mirror from it at release time. Packagist has a webhook on the MIRROR repo, so
# pushing master + a version tag there triggers indexing within 1-2 minutes.
#
# NOTE: one-time setup (already done): create the public mirror repo, submit it
# on Packagist, and add the Packagist webhook to the MIRROR repo (not this one).
#
# FAILURE POLICY (no silent error-swallowing):
#   - The git operations (subtree split + push mirror master + push tag) ARE the
#     publish. Each push is wrapped in `retry` to ride out transient git/network
#     errors and HARD-fails the step if it ultimately fails. These run with host
#     git (present on the release agent) — no container needed.
#   - A missing or invalid composer.json is a real prerequisite failure and now
#     HARD-fails (it used to silent-skip).
#   - Packagist *indexing* (the package appearing on packagist.org) is
#     webhook-driven and genuinely lags, so that — and only that — is
#     retry-then-tolerate with a :warning: (the mirror push, i.e. the real
#     publish, has already succeeded).
#
# Dry-run: validate composer.json, skip the split + push.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_release_inputs
skip_unless_release_type "client-php" full,post-maven

log_step "Publish PHP client $RELEASE_VERSION (dry-run=$DRY_RUN)"

MODULE_DIR="$REPO_ROOT/mockserver-client-php"
MIRROR_REPO="https://github.com/mock-server/mockserver-client-php.git"
PREFIX="mockserver-client-php"
TAG="${RELEASE_VERSION}"

# Validate composer.json exists. HARD: a missing manifest means the package is
# broken — fail loud rather than silently skipping the PHP client publish.
if [[ ! -f "$MODULE_DIR/composer.json" ]]; then
  log_error "composer.json not found in $MODULE_DIR — cannot publish PHP client"
  exit 1
fi

# Validate composer.json is valid JSON. HARD for the same reason — an invalid
# manifest is a real defect, not a transient condition to tolerate.
require_cmd jq
if ! jq empty "$MODULE_DIR/composer.json" 2>/dev/null; then
  log_error "composer.json is not valid JSON — cannot publish PHP client"
  exit 1
fi
log_info "composer.json: valid (name=$(jq -r '.name' "$MODULE_DIR/composer.json"))"

if is_dry_run; then
  log_dry "skip: git subtree split --prefix=$PREFIX"
  log_dry "skip: push split -> $MIRROR_REPO master"
  log_dry "skip: push tag $TAG -> $MIRROR_REPO"
  exit 0
fi

# Authenticate git pushes to github.com via the release github-token (sets an
# http extraheader + release identity) and ensure the credential is cleared on
# every exit path. Register the cleanup trap BEFORE configuring, so a failure
# inside configure_git_for_push can't leak a half-written credential.
trap 'clear_git_push_credentials' EXIT
configure_git_for_push

# Regenerate the mirror content: split mockserver-client-php/ into a commit whose
# root is the package. Deterministic and idempotent on a stable git version, so
# the mirror's master fast-forwards on each release. HARD: this is the first half
# of the publish — if the split fails there is nothing to push.
log_info "Splitting $PREFIX/ into a root-level commit"
SPLIT_REF=$(git -C "$REPO_ROOT" subtree split --prefix="$PREFIX")
log_info "subtree split: $SPLIT_REF"

# Push to the mirror's master. This IS the publish — HARD-fail, with retry to
# ride out transient git/network errors. A non-fast-forward here means the split
# history drifted (e.g. a git-version change); that is a real fault to fix, not
# to swallow — reseed once manually with:
#   git push --force $MIRROR_REPO <split>:refs/heads/master
log_info "Pushing split to mirror master"
retry 3 5 -- git -C "$REPO_ROOT" push "$MIRROR_REPO" "${SPLIT_REF}:refs/heads/master"
log_info "Pushed mirror master"

# Push the version tag to the mirror (idempotent). Packagist indexes this as the
# released version. Tags are immutable — never force-update an existing one.
# HARD-fail (with retry) when the tag is new: it is part of the publish.
if git -C "$REPO_ROOT" ls-remote --exit-code "$MIRROR_REPO" "refs/tags/$TAG" >/dev/null 2>&1; then
  log_info "Mirror tag $TAG already exists — skipping tag push"
else
  log_info "Pushing tag $TAG to mirror"
  retry 3 5 -- git -C "$REPO_ROOT" push "$MIRROR_REPO" "${SPLIT_REF}:refs/tags/$TAG"
  log_info "Pushed mirror tag $TAG"
fi

# Packagist indexing is webhook-driven and eventually-consistent: the package
# typically appears on packagist.org within 1-2 minutes of the mirror push.
# That lag is genuine propagation, not a publish failure — the mirror master +
# tag (the real publish) are already pushed — so this is the ONE step we
# retry-then-tolerate, surfaced with a :warning: rather than swallowed.
PACKAGE_NAME="$(jq -r '.name' "$MODULE_DIR/composer.json")"
log_info "Confirming Packagist indexed $PACKAGE_NAME@$TAG (webhook lags ~1-2 min)"
if ! retry 5 10 -- bash -c '
       url="https://repo.packagist.org/p2/'"$PACKAGE_NAME"'.json"
       curl -fsSL --max-time 30 "$url" 2>/dev/null | grep -q "\"version\":\"'"$TAG"'\""
     '; then
  log_info ":warning: Packagist has not indexed $PACKAGE_NAME@$TAG yet after retries — non-fatal (mirror master + tag pushed; the webhook indexes within minutes)"
fi

log_info "PHP client publish complete (mirror master + tag $TAG pushed)"
