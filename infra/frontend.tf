# The landing page and the app behind it: the Angular build in a private bucket, served at the
# site's name by a CloudFront distribution.

locals {
  site_name = "catalog.rgonz.dev"
}

resource "aws_s3_bucket" "frontend" {
  bucket = "rgonz-vehicle-catalog-frontend"
}

# No setting on the bucket or on a file in it can make either public.
resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# The bucket answers no one but this distribution, which signs what it asks for.
resource "aws_cloudfront_origin_access_control" "frontend" {
  name                              = "vehicle-catalog-frontend"
  description                       = "Lets the distribution read the bucket that holds the Angular build."
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# Listing is allowed beside reading so that a file that is not there is answered with 404. Without
# it the bucket answers 403, which does not say whether the file exists. A visitor still cannot
# list the bucket: only the address of the bucket itself lists it, and the distribution turns that
# address into the app's page.
resource "aws_s3_bucket_policy" "frontend" {
  bucket = aws_s3_bucket.frontend.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "OnlyThisDistributionReads"
      Effect    = "Allow"
      Principal = { Service = "cloudfront.amazonaws.com" }
      Action    = ["s3:GetObject", "s3:ListBucket"]
      Resource  = [aws_s3_bucket.frontend.arn, "${aws_s3_bucket.frontend.arn}/*"]
      Condition = {
        StringEquals = {
          "AWS:SourceArn" = aws_cloudfront_distribution.site.arn
        }
      }
    }]
  })
}

# The certificate for the site's name. AWS issues it once the record in the output
# certificate_validation_record is in DNS, which is kept at Cloudflare and changed by hand.
resource "aws_acm_certificate" "site" {
  domain_name       = local.site_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

# Waits until the certificate has been issued, so that the distribution is not made before it can
# use it.
resource "aws_acm_certificate_validation" "site" {
  certificate_arn = aws_acm_certificate.site.arn
}

# Answers the app's routes with the app's page. Custom error responses would do that for the
# whole distribution, and so would turn a 404 from the API into the app's page as well.
resource "aws_cloudfront_function" "app_routes" {
  name    = "vehicle-catalog-app-routes"
  runtime = "cloudfront-js-2.0"
  comment = "Answers the app's routes with the app's page."
  publish = true
  code    = file("${path.module}/functions/app_routes.js")
}

resource "aws_cloudfront_distribution" "site" {
  aliases             = [local.site_name]
  comment             = "vehicle-catalog"
  default_root_object = "index.html"
  enabled             = true
  is_ipv6_enabled     = true

  origin {
    origin_id                = "frontend"
    domain_name              = aws_s3_bucket.frontend.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.frontend.id
  }

  default_cache_behavior {
    target_origin_id       = "frontend"
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    # CachingOptimized, a cache policy that AWS manages.
    cache_policy_id = "658327ea-f89d-4fab-a63d-7e88639e58f6"

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.app_routes.arn
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  viewer_certificate {
    acm_certificate_arn      = aws_acm_certificate_validation.site.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }
}

# What publishing a build takes: putting its files in the bucket, removing the ones the build no
# longer has, and telling the distribution to fetch them anew. The distribution is found by the
# site's name, which takes listing the account's distributions.
resource "aws_iam_role_policy" "deploy_frontend" {
  name = "publish-frontend"
  role = aws_iam_role.deploy.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "SeeWhatIsPublished"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = aws_s3_bucket.frontend.arn
      },
      {
        Sid      = "PublishTheBuild"
        Effect   = "Allow"
        Action   = ["s3:DeleteObject", "s3:PutObject"]
        Resource = "${aws_s3_bucket.frontend.arn}/*"
      },
      {
        Sid      = "FindTheDistribution"
        Effect   = "Allow"
        Action   = ["cloudfront:ListDistributions"]
        Resource = "*"
      },
      {
        Sid      = "FetchTheBuildAnew"
        Effect   = "Allow"
        Action   = ["cloudfront:CreateInvalidation"]
        Resource = aws_cloudfront_distribution.site.arn
      },
    ]
  })
}
