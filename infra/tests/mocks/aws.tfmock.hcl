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
