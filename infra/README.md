# Infrastructure

What the app needs in AWS, as Terraform. Everything is in the region `us-east-1`.

- `backup.tf`: the bucket that keeps the nightly backups of the database, and the host's leave to
  write them.
- `budget.tf`: an alert by email once the account's costs for the month pass US$60.
- `frontend.tf`: the site at `catalog.rgonz.dev`. The Angular build is in a private bucket, and a
  CloudFront distribution serves it under a certificate for that name.
- `github_actions.tf`: how GitHub Actions reaches AWS without stored keys, and the two roles it can
  take on.
- `host.tf`: the host, one machine that answers everything under `/api`. It runs the stack in
  `deploy/`, and nothing but CloudFront reaches its HTTPS port.
- `login.tf`: who signs in. An Amazon Cognito user pool with its sign-in page, a group for each
  role, and the three demo accounts, whose passwords are public and shown on the landing page.
- `release.tf`: where the backend's images are kept, and the one thing the pipeline can have the
  host do, which is to release one of them.
- `telemetry.tf`: where the backend's log, traces, and metrics are kept in Amazon CloudWatch, the
  alarm that goes off when the backend is not ready, and the dashboard.

DNS for `rgonz.dev` is kept at Cloudflare and changed by hand. Every record named here is a
DNS-only record there. Cloudflare makes a new record a proxied one unless told otherwise, and a
proxied record answers with Cloudflare's own addresses, as if the record were not there.

## How a change reaches AWS

- **A pull request shows the plan.** The `Plan` workflow writes what Terraform would change as a
  comment on every pull request from a branch of this repository. It uses the role
  `vehicle-catalog-plan`, which reads and does nothing else.
- **The maintainer applies it**, by hand, with `terraform apply` in this directory. Nothing in the
  pipeline is allowed to change infrastructure. A change the pipeline's roles need is applied
  before it is merged, or the `Deploy` run of the merge fails for want of it.
- **`verify` checks it** without access to AWS: `terraform fmt -check`, `terraform validate`, the
  tests in `tests/`, and the test of the CloudFront function.

A pull request from a fork gets no access to AWS, and neither does one from Dependabot.

## How a commit reaches the site

What reaches main and passes `verify` goes live through the `Deploy` workflow, in this order:

1. It builds the backend's image and puts it in the registry under the commit's name.
2. It has the host release that image: fetch it, start it, and wait for it to say it is healthy.
   If it does not within about five minutes, the host starts the image before it again and the
   run fails.
3. It puts the frontend's build in the bucket and has the distribution fetch it anew.

It uses the role `vehicle-catalog-deploy`, which only a run on the main branch can take on and
which is allowed what those steps take and nothing else. On the host it can release an image and
do nothing more: `deploy/` reaches the host with `terraform apply`, not with a push.

Going back to the image before restores the image and not the database, so every migration
leaves the image before it able to run.

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

5. Give the database its password, which is kept in Parameter Store and nowhere else:

   ```sh
   aws ssm put-parameter --name /vehicle-catalog/database-password --type SecureString \
     --value "$(openssl rand -hex 32)"
   ```

   The database takes the password when it first starts and keeps it. A new value here does not
   change the database's own.

6. Create everything else. This waits until the certificate has been issued, which AWS says can
   take half an hour from when the record is there, and then until the distribution is ready:

   ```sh
   terraform apply
   ```

7. Point the site's name at the distribution: at Cloudflare, a CNAME record from `catalog` to the
   output `site_dns_target`.

8. Tell the pipeline which roles to take on:

   ```sh
   gh secret set AWS_PLAN_ROLE_ARN \
     --body "$(aws iam get-role --role-name vehicle-catalog-plan --query Role.Arn --output text)"
   gh secret set AWS_DEPLOY_ROLE_ARN \
     --body "$(aws iam get-role --role-name vehicle-catalog-deploy --query Role.Arn --output text)"
   ```

   A role's ARN carries the AWS account's number. It is a secret so that the number is in
   neither the repository nor a run's log, and the `Plan` workflow leaves it out of its comment
   too.

The site shows the app once a build has been published, which the next push to main does. The
same push releases the backend; before it, the API answers 502.

## The host

There is no SSH. A shell on the host is opened through Systems Manager, which needs the Session
Manager plugin for the AWS CLI:

```sh
aws ssm start-session --target "$(aws ec2 describe-instances \
  --filters Name=tag:Name,Values=vehicle-catalog Name=instance-state-name,Values=running \
  --query 'Reservations[0].Instances[0].InstanceId' --output text)"
```

The stack is in `/opt/vehicle-catalog` there, which only root reads. This shows what Caddy, the
backend, and the database say:

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

A run that failed leaves Caddy running as it was. Once what stopped it is put right, this has the
host run it again:

```sh
aws ssm start-associations-once --association-ids "$(aws ssm list-associations \
  --association-filter-list key=AssociationName,value=vehicle-catalog-host-stack \
  --query 'Associations[0].AssociationId' --output text)"
```

The database is on the host's own disk. It is kept across releases and restarts, and it is gone
with the host: a new machine image in `host.tf` makes a new host, which starts with an empty
database and no backend until the next release. A backup puts back what the old host held.

The image of any commit that is still in the registry is released by hand the way the pipeline
releases one, which is also how to go back to an earlier commit:

```sh
aws ssm send-command --document-name vehicle-catalog-release \
  --targets Key=tag:Name,Values=vehicle-catalog --parameters ImageTag=<the commit, in full>
```

`deploy/test/check.sh` runs the same stack on this machine against stand-ins for the certificate
authority, the registry, Parameter Store, the backups bucket, and the CloudWatch agent. It releases
images to it, among them two that do not come up healthy, and backs the database up and puts the
backup back. `verify` runs it too.

The secret the distribution sends the host is changed with
`terraform apply -replace=random_password.origin_secret`. The host has the new one within a
minute and the distribution some minutes later; in between, the API answers 403.

Terraform refuses to give up the host's address, because whoever is given it next could pass for
the host while the `origin-catalog` record still names it. To take the host down for good, remove
that record at Cloudflare first, and then the `prevent_destroy` line in `host.tf`.

## Backups

Every night at 02:30 UTC the host writes a copy of the database to the bucket
`rgonz-vehicle-catalog-backups`, named by when it was made. That is half an hour before the demo
reset, so a backup holds the day's work. A backup is kept for thirty days.

The host writes backups and does nothing else with them, so one is listed and fetched from a
workstation. On the host, this says how the last run went and when the next is due, and makes a
backup now:

```sh
systemctl list-timers vehicle-catalog-backup.timer
sudo journalctl --unit vehicle-catalog-backup.service --since yesterday
sudo systemctl start vehicle-catalog-backup.service
```

To put a backup back, fetch it on a workstation and send it to the host's database through a
session there. This replaces everything the database holds, so the backend is stopped for it:

```sh
aws s3 ls s3://rgonz-vehicle-catalog-backups/
aws s3 cp s3://rgonz-vehicle-catalog-backups/<the backup> .
```

Then, on the host, with the file copied there:

```sh
cd /opt/vehicle-catalog
sudo docker compose stop backend
sudo docker compose exec -T db pg_restore --username catalog --dbname catalog \
  --clean --if-exists --no-owner < <the backup>
sudo docker compose start backend
```

A backup made by a newer backend holds tables an older one does not know, so the image released
is the one that made the backup, or a later one.

## What the backend reports

It all goes to Amazon CloudWatch.

- **Its log** is the log group `/vehicle-catalog/backend`, where a line is kept for thirty days.
  Docker on the host writes it there. A line is one JSON object. Every request leaves one, with
  its method, path, status, and time, and with the id of its trace as `traceId`. A health check
  leaves none.

  ```sh
  aws logs tail /vehicle-catalog/backend --since 10m
  ```

- **Its traces** go to the CloudWatch agent, which runs on the host itself, and from there to
  X-Ray. X-Ray keeps every span for thirty days in the log group `aws/spans`, which is of its own
  making, and the CloudWatch console shows them under Application Signals, Transaction Search.
  Every request is traced but the health checks.
- **Its metrics** go to the same agent, which publishes them under the namespace
  `vehicle-catalog` together with two of the host's own. The dashboard `vehicle-catalog` shows
  them.

| Metric | What it says | Told apart by |
| --- | --- | --- |
| `health` | 1 while the backend is ready, 0 while it is not | nothing |
| `http.server.requests` | how many requests there were and how long each took | `outcome`, of which requests ordinarily have four |
| `jvm.heap.used` | the memory the backend's heap uses | nothing |
| `catalog.edit` | how long saving an edit of a working copy took | nothing |
| `catalog.copy` | how long copying a catalog into a new working copy took | nothing |
| `mem_used_percent` | the share of the host's memory in use | nothing |
| `disk_used_percent` | the share of the host's disk in use | nothing that varies |

CloudWatch counts a metric once for every value of what it is told apart by, which makes ten of
these, and charges for each one beyond ten. So the backend sends no metric that the alarm or the
dashboard does not use: `Telemetry.java` in the backend turns down every other. A metric is there
once it has first been sent, so the two times and the fourth outcome, a request that fails in the
backend, appear when there has been one.

The agent adds four labels of its own to each of the backend's metrics, and one of them is the
version of the library that sent it. The alarm and the dashboard therefore find a metric by its
name, whatever labels it carries. `docs/adr/0010` says why.

The alarm `vehicle-catalog-backend-health` goes off once the backend has not been ready for three
minutes, or has reported nothing in that time, which is what a stopped backend does. It tells no
one: it is there to be looked at.

```sh
aws cloudwatch describe-alarms --alarm-names vehicle-catalog-backend-health \
  --query 'MetricAlarms[0].StateValue'
```

On the host, this says whether the agent runs, and its own log is in
`/opt/aws/amazon-cloudwatch-agent/logs/`:

```sh
sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a status
```

That log gains a line a minute for each thing the backend times that did not happen in that
minute: `E! metric has a distribution with no entries`. It is the agent turning down an empty
report, and nothing is lost by it.

`docs/host-memory-measurements.md` has the memory the stack was measured to use, and
`docs/adr/0009` the size of the host that was chosen from it.

## Working with it

```sh
terraform plan     # what would change
terraform apply    # change it
terraform test     # who may take on each role, what each role may do, what reaches the host, and what it reports
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
`host.tf` and change by hand. The CloudWatch agent is the one Amazon Linux has in its packages
when the host is first set up.
