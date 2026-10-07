# Infrastructure

What the app needs in AWS, as Terraform. Everything is in the region `us-east-1`.

- `budget.tf`: an alert by email once the account's costs for the month pass US$60.
- `frontend.tf`: the site at `catalog.rgonz.dev`. The Angular build is in a private bucket, and a
  CloudFront distribution serves it under a certificate for that name.
- `github_actions.tf`: how GitHub Actions reaches AWS without stored keys, and the two roles it can
  take on.
- `host.tf`: the host, one machine that answers everything under `/api`. It runs the stack in
  `deploy/`, and nothing but CloudFront reaches its HTTPS port.

DNS for `rgonz.dev` is kept at Cloudflare and changed by hand. Every record named here is a
DNS-only record there. Cloudflare makes a new record a proxied one unless told otherwise, and a
proxied record answers with Cloudflare's own addresses, as if the record were not there.

## How a change reaches AWS

- **A pull request shows the plan.** The `Plan` workflow writes what Terraform would change as a
  comment on every pull request from a branch of this repository. It uses the role
  `vehicle-catalog-plan`, which reads and does nothing else.
- **The maintainer applies it**, by hand, with `terraform apply` in this directory. Nothing in the
  pipeline is allowed to change infrastructure.
- **`verify` checks it** without access to AWS: `terraform fmt -check`, `terraform validate`, the
  tests in `tests/`, and the test of the CloudFront function.

A pull request from a fork gets no access to AWS, and neither does one from Dependabot.

## How a build reaches the site

What reaches main and passes `verify` is published by the `Deploy` workflow: it builds the
frontend, puts the build in the bucket, and has the distribution fetch it anew. It uses the role
`vehicle-catalog-deploy`, which only a run on the main branch can take on and which is allowed
what publishing takes and nothing else.

## Before the first apply

These are done once, signed in with `aws sso login`. Until all of them are done, the `Plan` and
`Deploy` workflows fail, because they cannot take on their roles.

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

4. Ask for the site's certificate, which AWS issues once a record of its choosing is in DNS, and
   for the host's address:

   ```sh
   terraform init
   terraform apply -target=aws_acm_certificate.site -target=aws_eip.host
   ```

   The output `certificate_validation_record` is that record, and
   `terraform output certificate_validation_record` shows it again. Add it at Cloudflare, without
   the dot that ends its name and its value. It is there once this prints its value:

   ```sh
   dig +short CNAME <the record's name>
   ```

   The record stays for good: AWS renews the certificate only while it is there.

   Point the host's name at the host's address too: an A record from `origin-catalog` to the
   output `host_address`. The host asks for its certificate as it starts, and Let's Encrypt looks
   the name up then.

5. Create everything else. This waits until the certificate has been issued, which AWS says can
   take half an hour from when the record is there, and then until the distribution is ready:

   ```sh
   terraform apply
   ```

6. Point the site's name at the distribution: at Cloudflare, a CNAME record from `catalog` to the
   output `site_dns_target`.

7. Tell the pipeline which roles to take on:

   ```sh
   gh secret set AWS_PLAN_ROLE_ARN \
     --body "$(aws iam get-role --role-name vehicle-catalog-plan --query Role.Arn --output text)"
   gh secret set AWS_DEPLOY_ROLE_ARN \
     --body "$(aws iam get-role --role-name vehicle-catalog-deploy --query Role.Arn --output text)"
   ```

   A role's ARN carries the AWS account's number. It is a secret so that the number is in
   neither the repository nor a run's log, and the `Plan` workflow leaves it out of its comment
   too.

The site shows the app once a build has been published, which the next push to main does.

## The host

There is no SSH. A shell on the host is opened through Systems Manager, which needs the Session
Manager plugin for the AWS CLI:

```sh
aws ssm start-session --target "$(aws ec2 describe-instances \
  --filters Name=tag:Name,Values=vehicle-catalog Name=instance-state-name,Values=running \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)"
```

The stack is in `/opt/vehicle-catalog` there, which only root reads. This shows what Caddy says:

```sh
sudo docker compose --project-directory /opt/vehicle-catalog logs
```

A change to `deploy/` reaches the host with `terraform apply`, which sends the host the files and
has it start the stack anew. The host has Caddy check the files first, and keeps the stack as it
was if Caddy refuses them. The apply that first sets the host up waits for that run and fails if
it does. A later apply does not wait, and this says how the last run went:

```sh
aws ssm list-associations \
  --association-filter-list key=AssociationName,value=vehicle-catalog-host-stack \
  --query 'Associations[0].Overview'
```

`deploy/test/check.sh` runs the same stack on this machine against a stand-in for the certificate
authority, which `verify` does too.

The secret the distribution sends the host is changed with
`terraform apply -replace=random_password.origin_secret`. The host has the new one within a
minute and the distribution some minutes later; in between, the API answers 403.

Terraform refuses to give up the host's address, because whoever is given it next could pass for
the host while the `origin-catalog` record still names it. To take the host down for good, remove
that record at Cloudflare first, and then the `prevent_destroy` line in `host.tf`.

## Working with it

```sh
terraform plan     # what would change
terraform apply    # change it
terraform test     # who may take on each role and read the bucket, and what each role may do
node --test functions/app_routes.test.mjs    # which paths are answered with the app's page
```

On a checkout without `terraform.tfvars`, give the address in the environment:

```sh
TF_VAR_budget_notification_email=someone@example.com terraform plan
```

## Versions

Terraform 1.15 or newer. The providers are pinned in `terraform.tf`, and `.terraform.lock.hcl`
records what was downloaded for them; both are kept in Git. The workflows install the Terraform
version they name. The host's image and the release of Docker Compose it is given are named in
`host.tf` and change by hand.
