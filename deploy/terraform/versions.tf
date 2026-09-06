terraform {
  required_version = ">= 1.10.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    # Only for the IAM propagation gate in iam.tf - see mootmaker-api#26.
    time = {
      source  = "hashicorp/time"
      version = "~> 0.12"
    }
  }

  # Bucket/key/region/locking are supplied via backend.hcl (see
  # mootmaker-bootstrap-terraform's README for how remote state works).
  backend "s3" {}
}
