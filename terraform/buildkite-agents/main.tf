provider "aws" {
  region  = var.region
  profile = "mockserver-build"
}

module "buildkite_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "default"

  instance_types          = var.instance_types
  min_size                = var.min_size
  max_size                = var.max_size
  on_demand_percentage    = var.on_demand_percentage
  on_demand_base_capacity = 1

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns         = [aws_iam_policy.read_build_secrets.arn, aws_iam_policy.ecr_public_push.arn, aws_iam_policy.dependency_cache.arn]
}

module "buildkite_trigger_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver-trigger"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "trigger"

  instance_types          = var.trigger_instance_types
  min_size                = var.trigger_min_size
  max_size                = var.trigger_max_size
  on_demand_percentage    = 0
  on_demand_base_capacity = 0

  agents_per_instance         = 4
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns         = [aws_iam_policy.read_build_secrets.arn]
}

# Dedicated PERFORMANCE queue. Reproducible numbers matter far more here than
# throughput, so unlike the default queue this is a SINGLE fixed-performance
# instance type (no mixed c5/c5a/m5 microarchitectures), 100% on-demand (no Spot
# reclaim mid-run), max ONE instance (two perf runs never contend), and
# scale-to-zero (min_size 0 — AGENTS.md hard constraint: zero idle cost). The
# daily run launches the box on demand and the ASG terminates it when idle.
module "buildkite_perf_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver-perf"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "perf"

  instance_types          = var.perf_instance_types
  min_size                = var.perf_min_size # MUST stay 0 (scale to zero)
  max_size                = var.perf_max_size # 1 — never run two perf jobs at once
  on_demand_percentage    = 100               # on-demand only — a Spot reclaim would poison the baseline
  on_demand_base_capacity = 0                 # base 0 so it truly scales to zero

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns = [
    aws_iam_policy.read_build_secrets.arn, # Buildkite API token (commit guard / compare)
    aws_iam_policy.perf_results.arn,       # S3 results history bucket
  ]
}

module "buildkite_release_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver-release"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "release"

  instance_types          = var.instance_types
  min_size                = var.release_min_size
  max_size                = var.release_max_size
  on_demand_percentage    = 100
  on_demand_base_capacity = 1

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns = [
    aws_iam_policy.read_build_secrets.arn,
    aws_iam_policy.read_release_secrets.arn,
    aws_iam_policy.ecr_public_push.arn,
    aws_iam_policy.release_website_tfstate.arn,
    aws_iam_policy.dependency_cache.arn,
  ]
}
