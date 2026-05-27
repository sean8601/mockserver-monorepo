#!/usr/bin/env bash
# Build and publish the Jekyll documentation site.
#
# Dry-run: bundle install + jekyll build, skip S3 sync + CF invalidation.

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

require_cmd docker
require_release_inputs
skip_unless_release_type "website" full,post-maven

log_step "Publish website $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

JEKYLL_DIR="$REPO_ROOT/jekyll-www.mock-server.com"

log_info "Build Jekyll site"
rm -rf "$JEKYLL_DIR/_site"
in_docker "$RUBY_IMAGE" \
  -w /build/jekyll-www.mock-server.com \
  -v mockserver-bundle-cache:/usr/local/bundle \
  -- bash -ec '
    bundle install --quiet
    bundle exec jekyll build
  '

log_info "Copy legacy URL pages"
cd "$JEKYLL_DIR"
for f in mockserver_clients running_mock_server debugging_issues creating_expectations; do
  cp "_site/mock_server/$f.html" "_site/" 2>/dev/null || true
done

if is_dry_run; then
  log_dry "skip: aws s3 sync + CloudFront invalidation"
  log_info "Site built at: $JEKYLL_DIR/_site/"
else
  [[ -n "${WEBSITE_BUCKET:-}" ]] || { log_error "WEBSITE_BUCKET not set"; exit 1; }

  log_info "Assume website role"
  assume_website_role

  log_info "Sync to S3"
  aws s3 sync "$JEKYLL_DIR/_site/" "s3://$WEBSITE_BUCKET/" --delete \
    --exclude "versions/*" \
    --exclude "schema/*" \
    --exclude "index.yaml" \
    --exclude "*.tgz"

  # Hard requirement: a successful S3 sync without a CloudFront invalidation
  # would leave CDN edges serving the previous release's content for up to
  # 24 hours, while the pipeline reports success. versioned-site.sh now
  # populates DISTRIBUTION_ID on every release type (audit F-VS-01); a
  # missing value here means an upstream step broke and must surface, not
  # silently skip.
  [[ -n "${DISTRIBUTION_ID:-}" ]] || { log_error "DISTRIBUTION_ID not set — versioned-site.sh should have populated it via set_release_output (or the Buildkite adapter should have seeded it from meta-data)"; exit 1; }

  log_info "Invalidate CloudFront"
  aws cloudfront create-invalidation \
    --distribution-id "$DISTRIBUTION_ID" \
    --paths "/*"
fi

log_info "Website publish complete"
