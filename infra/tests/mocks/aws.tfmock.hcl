# What the stand-in for AWS answers where a made-up word would not do: an ARN has to look like one.
# The account in them is the one AWS uses in its own examples.
mock_resource "aws_acm_certificate" {
  defaults = {
    arn = "arn:aws:acm:us-east-1:123456789012:certificate/00000000-0000-0000-0000-000000000000"
  }
}

mock_resource "aws_cloudfront_function" {
  defaults = {
    arn = "arn:aws:cloudfront::123456789012:function/vehicle-catalog-app-routes"
  }
}

# The region is written out in deploy/, where it has to be the one everything else is in.
mock_data "aws_region" {
  defaults = {
    region = "us-east-1"
  }
}

# CloudFront's addresses are looked up by the name AWS gives their list.
mock_data "aws_ec2_managed_prefix_lists" {
  defaults = {
    ids = ["pl-cloudfront"]
  }
}

# A user pool's id is checked for its form wherever it is used.
mock_resource "aws_cognito_user_pool" {
  defaults = {
    id       = "us-east-1_Example01"
    endpoint = "cognito-idp.us-east-1.amazonaws.com/us-east-1_Example01"
  }
}
