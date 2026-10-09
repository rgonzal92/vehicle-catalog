# Where the spreadsheets that people export are kept. The worker writes a file there, and the API
# hands whoever asked for it a link that works for five minutes. A file is gone after a day: it is
# built from a catalog whenever someone asks, so nothing is lost with it.

resource "aws_s3_bucket" "exports" {
  bucket = "rgonz-vehicle-catalog-exports"
}

# No setting on the bucket or on a file in it can make either public. A file is read through a
# link that the API has signed, and in no other way.
resource "aws_s3_bucket_public_access_block" "exports" {
  bucket = aws_s3_bucket.exports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "exports" {
  bucket = aws_s3_bucket.exports.id

  rule {
    id     = "keep-one-day"
    status = "Enabled"

    filter {}

    expiration {
      days = 1
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# The host writes the files, reads them, which is what a link to one does in its name, and deletes
# them. It lists what the bucket holds, which the demo reset needs in order to empty it. It does
# nothing else with the bucket, and nothing with a file anywhere else.
resource "aws_iam_role_policy" "host_exports" {
  name = "exports"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "KeepExportedSpreadsheets"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.exports.arn}/*"
      },
      {
        Sid      = "ListThemToEmptyTheBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.exports.arn
      },
    ]
  })
}
