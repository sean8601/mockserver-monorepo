# Buildkite Pipelines

Terraform-managed Buildkite pipeline definitions for the MockServer project.

## Overview

This stack manages Buildkite pipelines using the [Buildkite Terraform provider](https://registry.terraform.io/providers/buildkite/buildkite/latest). Adding a new pipeline is done by adding an entry to the `pipelines` local in `pipelines.tf`.

## Pipelines

| Pipeline | File | Trigger | Purpose |
|----------|------|---------|---------|
| MockServer | `.buildkite/pipeline.yml` | Push to branches, PRs | CI build and test |
| Docker Push Maven | `.buildkite/docker-push-maven.yml` | Manual | Build and push `mockserver/mockserver:maven` CI image |

## Prerequisites

- AWS CLI with SSO profile `mockserver-build` authenticated (for the AWS provider used elsewhere in this stack)
- A Buildkite **classic API Access Token** exported as `BUILDKITE_API_TOKEN` before running `terraform plan`/`apply`:
  - Create one at https://buildkite.com/user/api-access-tokens scoped to org `mockserver` with `read_pipelines`, `write_pipelines`, and GraphQL enabled
  - This is **not** the `bk` CLI login (that is an OAuth token the GraphQL/Terraform API rejects)
  - Run only by a local admin — the token is never stored in Secrets Manager or granted to a build queue
- On macOS with Python 3.14+, set `export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib"` before running Terraform

## Usage

```bash
aws sso login --profile mockserver-build
export BUILDKITE_API_TOKEN=...   # classic API Access Token (see Prerequisites)
cd terraform/buildkite-pipelines
terraform init
terraform plan
terraform apply
```

## Adding a Pipeline

1. Create the pipeline YAML in `.buildkite/`
2. Add an entry to `local.pipelines` in `pipelines.tf`
3. Run `terraform apply`

## State

Remote state is stored in S3 (`buildkite-pipelines/terraform.tfstate`) in the same bucket as the `buildkite-agents` stack.
