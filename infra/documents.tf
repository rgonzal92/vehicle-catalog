# Where the documents that admins upload are kept. The API writes a file there when it is uploaded
# and removes it when its document is deleted, and the worker reads it. A file stays for as long
# as its document does: nothing is removed for its age.

resource "aws_s3_bucket" "documents" {
  bucket = "rgonz-vehicle-catalog-documents"
}

# No setting on the bucket or on a file in it can make either public. A file is read by the app
# and by no one else: there is no link to one.
resource "aws_s3_bucket_public_access_block" "documents" {
  bucket = aws_s3_bucket.documents.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "documents" {
  bucket = aws_s3_bucket.documents.id

  rule {
    id     = "clear-unfinished-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

# The host writes the files, reads them, and deletes them. It lists what the bucket holds, which
# the demo reset needs in order to empty it. It does nothing else with the bucket, and nothing
# with a file anywhere else.
resource "aws_iam_role_policy" "host_documents" {
  name = "documents"
  role = aws_iam_role.host.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "KeepUploadedDocuments"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:GetObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.documents.arn}/*"
      },
      {
        Sid      = "ListThemToEmptyTheBucket"
        Effect   = "Allow"
        Action   = "s3:ListBucket"
        Resource = aws_s3_bucket.documents.arn
      },
    ]
  })
}
