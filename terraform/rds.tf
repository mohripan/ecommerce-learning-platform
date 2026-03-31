# =============================================================================
# RDS PostgreSQL Instances (Order, Inventory, Analytics)
# =============================================================================

# -----------------------------------------------------------------------------
# Subnet Group
# -----------------------------------------------------------------------------

resource "aws_db_subnet_group" "main" {
  name       = "${local.cluster_name}-db-subnet"
  subnet_ids = aws_subnet.private[*].id

  tags = {
    Name = "${local.cluster_name}-db-subnet"
  }
}

# -----------------------------------------------------------------------------
# Security Group
# -----------------------------------------------------------------------------

resource "aws_security_group" "rds" {
  name_prefix = "${local.cluster_name}-rds-"
  description = "Allow PostgreSQL access from EKS nodes"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "PostgreSQL from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_eks_node_group.main.resources[0].remote_access_security_group_id]
  }

  # Allow access from any resource within the VPC (fallback)
  ingress {
    description = "PostgreSQL from VPC"
    from_port   = 5432
    to_port     = 5432
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
    Name = "${local.cluster_name}-rds-sg"
  }

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# RDS Instances
# -----------------------------------------------------------------------------

locals {
  databases = {
    order     = { name = "orderdb" }
    inventory = { name = "inventorydb" }
    analytics = { name = "analyticsdb" }
  }
}

resource "aws_db_instance" "main" {
  for_each = local.databases

  identifier     = "${local.cluster_name}-${each.key}"
  engine         = "postgres"
  engine_version = var.rds_engine_version
  instance_class = var.rds_instance_class

  allocated_storage     = var.rds_allocated_storage
  max_allocated_storage = var.rds_allocated_storage * 2
  storage_encrypted     = true

  db_name  = each.value.name
  username = "${each.key}_admin"
  manage_master_user_password = true

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  multi_az            = var.environment == "production"
  skip_final_snapshot = var.environment != "production"
  final_snapshot_identifier = var.environment == "production" ? "${local.cluster_name}-${each.key}-final" : null

  backup_retention_period = var.environment == "production" ? 7 : 1

  tags = {
    Name    = "${local.cluster_name}-${each.key}-db"
    Service = "${each.key}-service"
  }
}
