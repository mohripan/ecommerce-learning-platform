# =============================================================================
# Amazon MSK (Managed Streaming for Apache Kafka)
# =============================================================================

# -----------------------------------------------------------------------------
# Security Group
# -----------------------------------------------------------------------------

resource "aws_security_group" "msk" {
  name_prefix = "${local.cluster_name}-msk-"
  description = "Allow Kafka access from EKS nodes"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "Kafka plaintext from VPC"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  ingress {
    description = "Kafka TLS from VPC"
    from_port   = 9094
    to_port     = 9094
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  ingress {
    description = "Kafka IAM auth from VPC"
    from_port   = 9098
    to_port     = 9098
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  ingress {
    description = "ZooKeeper from VPC"
    from_port   = 2181
    to_port     = 2181
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "${local.cluster_name}-msk-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# MSK Cluster – 3 brokers across private subnets
# -----------------------------------------------------------------------------

resource "aws_msk_cluster" "main" {
  cluster_name           = "${local.cluster_name}-kafka"
  kafka_version          = var.msk_kafka_version
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type  = var.msk_instance_type
    client_subnets = aws_subnet.private[*].id

    storage_info {
      ebs_storage_info {
        volume_size = var.msk_ebs_volume_size
      }
    }

    security_groups = [aws_security_group.msk.id]
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS_PLAINTEXT"
      in_cluster    = true
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = {
    Name = "${local.cluster_name}-kafka"
  }
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/aws/msk/${local.cluster_name}"
  retention_in_days = 7

  tags = {
    Name = "${local.cluster_name}-msk-logs"
  }
}
