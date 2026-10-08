# Where the backups of the database are kept. The host writes one every night, half an hour before
# the demo reset, so a backup holds the day's work. infra/README.md says how one is put back.

resource "aws_s3_bucket" "backups" {
  bucket = "rgonz-vehicle-catalog-backups"
}

# No setting on the bucket or on a file in it can make either public.
resource "aws_s3_bucket_public_access_block" "backups" {
  bucket = aws_s3_bucket.backups.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# A backup written over another of the same name leaves the earlier one in place.
resource "aws_s3_bucket_versioning" "backups" {
  bucket = aws_s3_bucket.backups.id

  versioning_configuration {
    status = "Enabled"
  }
}

# A backup goes after thirty days, and so does one that another was written over.
resource "aws_s3_bucket_lifecycle_configuration" "backups" {
  bucket = aws_s3_bucket.backups.id

  rule {
    id     = "keep-thirty-days"
    status = "Enabled"

    filter {}

    expiration {
      days = 30
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  depends_on = [aws_s3_bucket_versioning.backups]
}

# The host writes backups and does nothing else with them: it reads none, lists none, and removes
# none. Whoever gets onto the host cannot take the backups or do away with them.
resource "aws_iam_role_policy" "host_back_up" {
  name = "back-up"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid      = "WriteBackups"
      Effect   = "Allow"
      Action   = ["s3:PutObject"]
      Resource = "${aws_s3_bucket.backups.arn}/*"
    }]
  })
}
