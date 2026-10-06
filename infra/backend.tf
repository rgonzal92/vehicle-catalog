# The state is kept in a versioned bucket, which is made by hand before the first apply: README.md
# says how. A lock file beside the state keeps two applies from running at once.
terraform {
  backend "s3" {
    bucket       = "rgonz-vehicle-catalog-terraform-state"
    key          = "terraform.tfstate"
    region       = "us-east-1"
    use_lockfile = true
  }
}
