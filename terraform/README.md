# Terraform Infrastructure

This directory contains all Terraform-managed infrastructure for MockServer.

```mermaid
graph TB
    subgraph "terraform/"
        direction TB
        BA["buildkite-agents/"]
        BP["buildkite-pipelines/"]
        WEB["website/"]
        SES["ses-email-forwarding/"]
    end

    BA -->|provisions| AWS["AWS eu-west-2\nBuild Agent Account"]
    BP -->|manages| BK["Buildkite\nPipeline Definitions"]
    WEB -->|provisions S3 + CloudFront + Route53\n+ cross-account IAM| AWSWEB["AWS us-east-1\nWebsite Account"]
    SES -->|provisions inbound email| AWSWEB

    subgraph AWS
        direction TB
        VPC[VPC + Subnets]
        ASG["AutoScaling Group\nSpot instances, 0-10"]
        SCALER[Lambda Autoscaler]
        AGENT[Buildkite Agents]
    end

    SCALER -->|scales| ASG
    ASG -->|launches| AGENT
    AGENT -->|runs in| VPC
```

## Modules

| Directory | Purpose | Provider |
|-----------|---------|----------|
| [`buildkite-agents/`](buildkite-agents/) | Buildkite CI build agent cluster | AWS (`eu-west-2`) |
| [`buildkite-pipelines/`](buildkite-pipelines/) | Buildkite pipeline definitions | Buildkite + AWS |
| [`website/`](website/) | Static-site S3 buckets, CloudFront distributions, Route 53 records, and cross-account IAM role for `mock-server.com` and every versioned subdomain | AWS (`us-east-1` + `eu-west-2`) |
| [`ses-email-forwarding/`](ses-email-forwarding/) | SES catch-all email forwarding for `mock-server.com` | AWS (`us-east-1`) |

## Prerequisites

- [Terraform](https://www.terraform.io/downloads) >= 1.15
- [AWS CLI](https://aws.amazon.com/cli/) with SSO profile `mockserver-build`
- AWS build agent account (see `~/mockserver-aws-ids.md`)

## Quick Start

The `buildkite-agents` module has a `run.sh` wrapper that handles AWS authentication and runs Terraform. See the individual module READMEs for details.

## State Management

All modules store state remotely in S3 with native file locking:

| Resource | Region |
|----------|--------|
| S3 Bucket (see `~/mockserver-aws-ids.md`) | `eu-west-2` |

The state backend is bootstrapped via `buildkite-agents/bootstrap/`. See the [bootstrap README](buildkite-agents/bootstrap/) for details.
