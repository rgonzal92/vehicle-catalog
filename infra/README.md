# Infrastructure

What the app needs in AWS, as Terraform. Everything is in the region `us-east-1`.

- `budget.tf`: an alert by email once the account's costs for the month pass US$60.
- `github_actions.tf`: how GitHub Actions reaches AWS without stored keys, and the two roles it can
  take on.

## How a change reaches AWS

- **A pull request shows the plan.** The `Plan` workflow writes what Terraform would change as a
  comment on every pull request from a branch of this repository. It uses the role
  `vehicle-catalog-plan`, which reads and does nothing else.
- **The maintainer applies it**, by hand, with `terraform apply` in this directory. Nothing in the
  pipeline is allowed to change infrastructure.
- **`verify` checks it** without access to AWS: `terraform fmt -check`, `terraform validate`, and
  the tests in `tests/`.

A pull request from a fork gets no access to AWS, and neither does one from Dependabot. The role
`vehicle-catalog-deploy` is for deploying from the main branch and is allowed nothing until
something that deploys grants it what that needs.

## Before the first apply

These are done once, signed in with `aws sso login`. Until all five are done, the `Plan` workflow
fails on every pull request, because it cannot take on its role.

1. Make the bucket that holds the state, and have it keep earlier versions of the state:

   ```sh
   aws s3api create-bucket --bucket rgonz-vehicle-catalog-terraform-state --region us-east-1
   aws s3api put-bucket-versioning --bucket rgonz-vehicle-catalog-terraform-state \
     --versioning-configuration Status=Enabled
   ```

   A new bucket refuses public access and encrypts what it holds.

2. Say where the budget alert goes, in a file Git ignores:

   ```sh
   echo 'budget_notification_email = "someone@example.com"' > terraform.tfvars
   ```

3. Give the pipeline the same address, so that its plans match:

   ```sh
   gh secret set BUDGET_NOTIFICATION_EMAIL
   ```

4. Create everything:

   ```sh
   terraform init
   terraform apply
   ```

5. Tell the pipeline which role to take on:

   ```sh
   gh secret set AWS_PLAN_ROLE_ARN \
     --body "$(aws iam get-role --role-name vehicle-catalog-plan --query Role.Arn --output text)"
   ```

   The role's name carries the AWS account's number. It is a secret so that the number is in
   neither the repository nor a run's log, and the workflow leaves it out of its comment too.

## Working with it

```sh
terraform plan     # what would change
terraform apply    # change it
terraform test     # who may take on each role, and what the plan role may do
```

On a checkout without `terraform.tfvars`, give the address in the environment:

```sh
TF_VAR_budget_notification_email=someone@example.com terraform plan
```

## Versions

Terraform 1.15 or newer. The AWS provider is pinned in `terraform.tf`, and `.terraform.lock.hcl`
records what was downloaded for it; both are kept in Git. The workflows install the Terraform
version they name.
