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
# Best-effort/soft: any failure here is non-fatal so it never blocks a release.
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

# Validate composer.json exists
if [[ ! -f "$MODULE_DIR/composer.json" ]]; then
  log_info "WARNING: composer.json not found in $MODULE_DIR — skipping client-php publish (non-fatal)"
  exit 0
fi

# Validate composer.json is valid JSON
if command -v jq >/dev/null 2>&1; then
  if ! jq empty "$MODULE_DIR/composer.json" 2>/dev/null; then
    log_info "WARNING: composer.json is not valid JSON — skipping client-php publish (non-fatal)"
    exit 0
  fi
  log_info "composer.json: valid (name=$(jq -r '.name' "$MODULE_DIR/composer.json"))"
fi

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
# the mirror's master fast-forwards on each release.
log_info "Splitting $PREFIX/ into a root-level commit"
SPLIT_REF=$(git -C "$REPO_ROOT" subtree split --prefix="$PREFIX") || {
  log_info "WARNING: git subtree split failed — skipping client-php publish (non-fatal)"
  exit 0
}
log_info "subtree split: $SPLIT_REF"

# Push to the mirror's master. A non-fast-forward here means the split history
# drifted (e.g. a git-version change) — soft-skip and reseed manually once with:
#   git push --force $MIRROR_REPO <split>:refs/heads/master
git -C "$REPO_ROOT" push "$MIRROR_REPO" "${SPLIT_REF}:refs/heads/master" || {
  log_info "WARNING: could not push to mirror master (non-fast-forward?) — skipping (non-fatal)"
  exit 0
}
log_info "Pushed mirror master"

# Push the version tag to the mirror (idempotent). Packagist indexes this as the
# released version. Tags are immutable — never force-update an existing one.
if git -C "$REPO_ROOT" ls-remote --exit-code "$MIRROR_REPO" "refs/tags/$TAG" >/dev/null 2>&1; then
  log_info "Mirror tag $TAG already exists — skipping tag push"
else
  git -C "$REPO_ROOT" push "$MIRROR_REPO" "${SPLIT_REF}:refs/tags/$TAG" || {
    log_info "WARNING: could not push tag $TAG to mirror — skipping (non-fatal)"
    exit 0
  }
  log_info "Pushed mirror tag $TAG"
fi

log_info "PHP client publish complete (mirror master + tag $TAG pushed; Packagist indexes via webhook within 1-2 minutes)"
