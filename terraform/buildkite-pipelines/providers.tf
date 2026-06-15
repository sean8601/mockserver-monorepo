# Buildkite API token for managing these pipelines (needs read_pipelines,
# write_pipelines, and GraphQL scopes). This stack is run only by a local admin,
# so the token is supplied from the admin's own environment rather than from a
# shared secret:
#
#   export BUILDKITE_API_TOKEN=...   # a classic API Access Token, NOT the `bk`
#                                    # CLI's OAuth login (that token is not
#                                    # accepted by the GraphQL/Terraform API)
#
# Create one at https://buildkite.com/user/api-access-tokens scoped to the
# `mockserver` org with read_pipelines + write_pipelines + GraphQL enabled.
#
# Previously the provider read this from Secrets Manager
# (mockserver-build/buildkite-tf-token). That secret was removed 2026-06-15: its
# stored token had gone stale (Buildkite returned 401) and this stack was its
# only consumer, so it was dead weight. No build queue ever had access to it.
variable "buildkite_api_token" {
  type        = string
  sensitive   = true
  default     = null
  description = "Buildkite API Access Token (read_pipelines, write_pipelines, GraphQL). Defaults to the BUILDKITE_API_TOKEN environment variable when unset."
}

provider "aws" {
  region  = "eu-west-2"
  profile = "mockserver-build"
}

provider "buildkite" {
  organization = "mockserver"
  # When var.buildkite_api_token is null the provider falls back to its native
  # BUILDKITE_API_TOKEN environment-variable default, so a logged-in admin just
  # exports the token and runs terraform — no -var needed.
  api_token = var.buildkite_api_token
}
