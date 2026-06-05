# ---------------------------------------------------------------------------
# Secrets Manager secrets and per-secret IAM policies
# ---------------------------------------------------------------------------
# Each secret has its own narrowly-scoped IAM policy so queues receive only
# the credentials they actually consume.  Attachment to queues is in main.tf.
#
# Queue -> secret mapping (verified by grepping .buildkite/scripts):
#   default:  buildkite-api-token (generate-pipeline.sh change detection),
#             dockerhub (docker-login.sh for snapshot push),
#             sonatype (java-deploy-snapshot.sh, master-only)
#   trigger:  buildkite-api-token (trigger-pipeline.sh orchestration)
#   perf:     buildkite-api-token (perf-test-guard.sh commit comparison)
#   release:  buildkite-api-token, dockerhub, sonatype, pypi, rubygems,
#             plus release-only secrets in read_release_secrets
# ---------------------------------------------------------------------------

# --- Secret resources -------------------------------------------------------

resource "aws_secretsmanager_secret" "dockerhub" {
  name        = "mockserver-build/dockerhub"
  description = "Docker Hub credentials for pushing mockserver CI and release images"
}

resource "aws_secretsmanager_secret" "buildkite_api_token" {
  name        = "mockserver-build/buildkite-api-token"
  description = "Buildkite API token for Terraform pipeline management (GraphQL + REST scopes)"
}

resource "aws_secretsmanager_secret" "sonatype" {
  name        = "mockserver-build/sonatype"
  description = "Sonatype OSSRH credentials for Maven snapshot and release deployment"
}

resource "aws_secretsmanager_secret" "pypi" {
  name        = "mockserver-build/pypi"
  description = "PyPI API token for publishing mockserver-client Python package"
}

resource "aws_secretsmanager_secret" "rubygems" {
  name        = "mockserver-build/rubygems"
  description = "RubyGems API key for publishing mockserver-client Ruby gem"
}

resource "aws_secretsmanager_secret" "gpg_key" {
  name        = "mockserver-release/gpg-key"
  description = "GPG private key and passphrase for Maven Central artifact signing"
}

resource "aws_secretsmanager_secret" "github_token" {
  name        = "mockserver-release/github-token"
  description = "GitHub PAT for creating releases and Homebrew PRs"
}

resource "aws_secretsmanager_secret" "totp_seed" {
  name        = "mockserver-release/totp-seed"
  description = "TOTP shared secret for release authorization"
}

resource "aws_secretsmanager_secret" "npm_token" {
  name        = "mockserver-release/npm-token"
  description = "npm automation token for publishing packages"
}

resource "aws_secretsmanager_secret" "swaggerhub" {
  name        = "mockserver-release/swaggerhub"
  description = "SwaggerHub API key for publishing OpenAPI spec"
}

resource "aws_secretsmanager_secret" "website_role" {
  name        = "mockserver-release/website-role"
  description = "IAM role ARN for cross-account website access"
}

# --- Per-secret IAM policies ------------------------------------------------

# Buildkite API token: consumed by trigger-pipeline.sh (trigger queue),
# generate-pipeline.sh (default queue), perf-test-guard.sh (perf queue),
# cleanup-closed-pr-builds.sh (default queue), and release scripts.
#
# LIVE FOLLOW-UP: scope this to a READ-ONLY Buildkite API token (current
# token has write scope for build creation). Create a separate read-only
# token for change-detection and a write-scoped token only for trigger queue.
resource "aws_iam_policy" "read_buildkite_api_token" {
  name        = "buildkite-read-buildkite-api-token"
  description = "Allow Buildkite agents to read the Buildkite API token from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.buildkite_api_token.arn]
    }]
  })
}

# Docker Hub credentials: consumed by docker-login.sh for snapshot push
# (default queue, master-only) and release image push (release queue).
#
# LIVE FOLLOW-UP: create a separate scoped Docker Hub token for snapshot
# pushes (default queue) vs release pushes (release queue) so a compromised
# default agent cannot push release-tagged images.
resource "aws_iam_policy" "read_dockerhub_secret" {
  name        = "buildkite-read-dockerhub-secret"
  description = "Allow Buildkite agents to read Docker Hub credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = "secretsmanager:GetSecretValue"
      Resource = [aws_secretsmanager_secret.dockerhub.arn]
    }]
  })
}

# Build secrets for the DEFAULT queue: buildkite-api-token + sonatype.
# Docker Hub is handled by read_dockerhub_secret (separate policy).
resource "aws_iam_policy" "read_build_secrets_default" {
  name        = "buildkite-read-build-secrets-default"
  description = "Allow default-queue Buildkite agents to read build credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "secretsmanager:GetSecretValue"
      Resource = [
        aws_secretsmanager_secret.buildkite_api_token.arn,
        aws_secretsmanager_secret.sonatype.arn,
      ]
    }]
  })
}

# Build secrets for the RELEASE queue: buildkite-api-token + sonatype + pypi + rubygems.
# Docker Hub is handled by read_dockerhub_secret (separate policy).
# Release-only secrets (GPG, GitHub, npm, etc.) are in read_release_secrets.
resource "aws_iam_policy" "read_build_secrets_release" {
  name        = "buildkite-read-build-secrets-release"
  description = "Allow release-queue Buildkite agents to read build credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = "secretsmanager:GetSecretValue"
      Resource = [
        aws_secretsmanager_secret.buildkite_api_token.arn,
        aws_secretsmanager_secret.sonatype.arn,
        aws_secretsmanager_secret.pypi.arn,
        aws_secretsmanager_secret.rubygems.arn,
      ]
    }]
  })
}

# Release-only secrets (unchanged).
resource "aws_iam_policy" "read_release_secrets" {
  name        = "buildkite-read-release-secrets"
  description = "Allow Buildkite agents to read release credentials from Secrets Manager"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = "secretsmanager:GetSecretValue"
        Resource = [
          aws_secretsmanager_secret.gpg_key.arn,
          aws_secretsmanager_secret.github_token.arn,
          aws_secretsmanager_secret.totp_seed.arn,
          aws_secretsmanager_secret.npm_token.arn,
          aws_secretsmanager_secret.swaggerhub.arn,
          aws_secretsmanager_secret.website_role.arn,
        ]
      },
      {
        # Cross-account assume of the website-release role.
        # Account ID is the mockserver-website account (014848309742). The
        # target role's trust policy is already scoped to a specific build-account
        # role ARN, so this is defence-in-depth on the source side.
        Effect   = "Allow"
        Action   = "sts:AssumeRole"
        Resource = "arn:aws:iam::014848309742:role/mockserver-release-website"
      }
    ]
  })
}
