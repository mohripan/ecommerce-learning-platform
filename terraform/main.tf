# =============================================================================
# Distributed Order & Fulfillment Platform - Terraform Configuration
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 2.0"
    }
  }

  # -------------------------------------------------------------------------
  # S3 Backend Configuration
  # -------------------------------------------------------------------------
  # Uncomment the block below to use S3 as a remote backend for state storage.
  # Before enabling, create the S3 bucket and DynamoDB table:
  #
  #   aws s3api create-bucket \
  #     --bucket <your-project>-terraform-state \
  #     --region ap-southeast-1 \
  #     --create-bucket-configuration LocationConstraint=ap-southeast-1
  #
  #   aws dynamodb create-table \
  #     --table-name <your-project>-terraform-locks \
  #     --attribute-definitions AttributeName=LockID,AttributeType=S \
  #     --key-schema AttributeName=LockID,KeyType=HASH \
  #     --billing-mode PAY_PER_REQUEST \
  #     --region ap-southeast-1
  #
  # backend "s3" {
  #   bucket         = "ecommerce-platform-terraform-state"
  #   key            = "infrastructure/terraform.tfstate"
  #   region         = "ap-southeast-1"
  #   dynamodb_table = "ecommerce-platform-terraform-locks"
  #   encrypt        = true
  # }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

provider "kubernetes" {
  host                   = aws_eks_cluster.main.endpoint
  cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", aws_eks_cluster.main.name]
  }
}

provider "helm" {
  kubernetes {
    host                   = aws_eks_cluster.main.endpoint
    cluster_ca_certificate = base64decode(aws_eks_cluster.main.certificate_authority[0].data)

    exec {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", aws_eks_cluster.main.name]
    }
  }
}

# Fetch available AZs in the configured region
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  cluster_name = "${var.project_name}-${var.environment}"
  azs          = slice(data.aws_availability_zones.available.names, 0, 3)
}
