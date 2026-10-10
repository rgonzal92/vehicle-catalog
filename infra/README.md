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
backend, the worker, and the database say:

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
authority, the registry, Parameter Store, the backups bucket, the CloudWatch agent, and the queue
of the jobs. It releases images to it, among them three that do not come up healthy, has the
worker do a job, and backs the database up and puts the backup back. `verify` runs it too.

The secret the distribution sends the host is changed with
`terraform apply -replace=random_password.origin_secret`. The host has the new one within a
minute and the distribution some minutes later; in between, the API answers 403.

The host reads four parameters and no other, all under `/vehicle-catalog/`: `origin-secret`,
`database-password`, `login-client-secret`, and `openai-api-key`, which it starts without when
there is none. The policy AWS has for Systems Manager lets a machine read every parameter the
account holds, so the host's role has a policy of its own that refuses the rest. When the app
comes to need another secret, name it in two places: where `deploy/release.sh` reads it, and in
`host_parameters` in `host.tf`. `terraform test` fails while the two differ, and one apply takes
both to the host.

Terraform refuses to give up the host's address, because whoever is given it next could pass for
the host while the `origin-catalog` record still names it. To take the host down for good, remove
that record at Cloudflare first, and then the `prevent_destroy` line in `host.tf`.

## Jobs and the worker

Work that follows a change, such as telling the owner of a catalog that it was approved, is a job.
The API writes a job into the database with the change that causes it, and never talks to a queue.
The worker does the jobs. It is the backend's own image, run a second time on the host with the
profile `worker`: it sends a message for each new job to a queue, receives the messages, and does
the job each one names. `docs/adr/0011` says why it is built this way.

A release starts the API and the worker from the new image, and has succeeded when both are
healthy. When either is not, both go back to the image that ran before.

| Queue | What is on it |
| --- | --- |
| `vehicle-catalog-jobs` | a message for each job that is waiting or being done |
| `vehicle-catalog-jobs-failed` | the messages that were received three times and never deleted, kept for fourteen days |

A message that the worker has received is hidden for two minutes. If the worker has not deleted it
by then, which it does once the job's work is saved, the queue delivers it again.

This says how many messages wait on each queue, and how many are being handled:

```sh
for queue in vehicle-catalog-jobs vehicle-catalog-jobs-failed; do
  aws sqs get-queue-attributes --queue-url "$(aws sqs get-queue-url --queue-name "$queue" \
    --query QueueUrl --output text)" \
    --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible \
    --query Attributes
done
```

The worker writes a line for every job it does, in the backend's log group under the stream
`worker`:

```sh
aws logs tail /vehicle-catalog/backend --log-stream-names worker --since 10m
```

A job that has not been done is in the database with the status `QUEUED`, and a message that has
not been sent to the queue yet is in `outbox` without a time in `sent_at`. With the worker stopped,
both wait there and nothing else is affected.

A job whose work fails is tried again each time its message is delivered. When the third try has
failed, the job is Failed and the queue moves its message to `vehicle-catalog-jobs-failed`. The
alarm `vehicle-catalog-jobs-failed` goes off while that queue holds a message. It tells no one: it
is there to be looked at.

A try can also end without the worker keeping what went wrong, as when the worker is stopped or
killed in the middle of it. The queue gives such a job's message up all the same, after three
deliveries, and the worker then makes the job Failed when it next looks through the dead-letter
queue, which it does every minute. Such a job has no failure of its own to show: the Jobs page
says of it that the queue gave up its message, and no try at the job said why. The worker's log
around the times of its deliveries is where to look.

```sh
aws cloudwatch describe-alarms --alarm-names vehicle-catalog-jobs-failed \
  --query 'MetricAlarms[0].StateValue'
```

The Jobs page, which an admin reaches from the sidebar, lists the jobs with what the last failed
try of each said. Once what made a job fail is put right, Retry there has it tried once more. When
it has been done, the worker takes its message out of the dead-letter queue within a minute, and
the alarm is quiet again.

A second alarm, `vehicle-catalog-jobs-waiting`, goes off when the oldest message on
`vehicle-catalog-jobs` has waited for more than ten minutes. A job that fails every time has left
that queue after about six, so this alarm means that the worker is stopped or stuck, and the first
that a job needs someone to look at it. It tells no one either.

```sh
aws cloudwatch describe-alarms --alarm-names vehicle-catalog-jobs-waiting \
  --query 'MetricAlarms[0].StateValue'
```

The dashboard `vehicle-catalog` shows how many messages wait on each of the two queues and how
long the oldest on `vehicle-catalog-jobs` has waited, which SQS reports at no charge, and how many
jobs were done a minute and how long they took, which the worker reports.

## Exported spreadsheets

Whoever may open a catalog can have it as a spreadsheet. The worker builds the file and writes it
to the bucket `rgonz-vehicle-catalog-exports`, under the export's id. The API then sends whoever
asked for it to a link that it has signed, which works for five minutes. Nothing in the bucket is
public, and a file is gone a day after it was written: it is built anew whenever someone asks.
The demo reset empties the bucket with everything else that visitors left.

```sh
aws s3 ls s3://rgonz-vehicle-catalog-exports/
```

## The language model

The app asks a language model of OpenAI's, `gpt-6-luna`, to suggest things that a person then
checks and saves. Its key is the one secret that Terraform neither makes nor keeps in its state: it
is put into Parameter Store by hand, as `/vehicle-catalog/openai-api-key`, and the host reads it
there whenever the stack is started. Without it the stack runs as before, and the app says that
the model cannot be asked.

This puts the key in place, or replaces it. It asks for the key and shows nothing of it, so the key
is in no file, in no history of a shell, and in no list of what runs:

```sh
read -rs -p 'The key: ' key && printf %s "$key" | aws ssm put-parameter \
  --name /vehicle-catalog/openai-api-key --type SecureString --overwrite \
  --value file:///dev/stdin; unset key
```

The host takes a new key at its next release. This has it take the key now, by starting the stack
again with the image that runs:

```sh
aws ssm send-command --document-name AWS-RunShellScript \
  --targets Key=tag:Name,Values=vehicle-catalog \
  --parameters commands=/opt/vehicle-catalog/release.sh
```

To take the key away, delete the parameter and start the stack again in the same way:

```sh
aws ssm delete-parameter --name /vehicle-catalog/openai-api-key
```

What the model may cost is limited by the app itself: US$1.00 in a UTC calendar day for everyone
together, and US$0.25 of it for one account. A request that would pass either is refused before
OpenAI is contacted, and the allowances renew at 00:00 UTC. The demo reset does not give them
back. `docs/adr/0014` says how it is counted. On the host, this says what today has cost so far:

```sh
cd /opt/vehicle-catalog
sudo docker compose exec -T db psql --username catalog --dbname catalog --command \
  "SELECT purpose, count(*) AS requests, sum(coalesce(spent, reserved)) AS dollars
   FROM ai_spend WHERE day = (now() AT TIME ZONE 'UTC')::date GROUP BY purpose"
```

A limit of OpenAI's own, set for the key's project in OpenAI's console, is the line behind that
one.

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

  A request and the job it causes are one trace: the job's span has the request's span as its
  parent, and the line the API writes for the request and the line the worker writes for the job
  carry the same `traceId`. With a trace's id from either line, this lists its spans, and the
  second command the lines of the worker that belong to it:

  ```sh
  aws logs filter-log-events --log-group-name aws/spans \
    --filter-pattern '{ $.traceId = "<the trace id>" }' \
    --query 'events[].message' --output text | jq '{name, spanId, parentSpanId}'
  aws logs filter-log-events --log-group-name /vehicle-catalog/backend \
    --log-stream-names worker --filter-pattern '{ $.traceId = "<the trace id>" }' \
    --query 'events[].message' --output text | jq .message
  ```
- **Its metrics** go to the same agent, which publishes them under the namespace
  `vehicle-catalog` together with two of the host's own. The dashboard `vehicle-catalog` shows
  them. The API sends the first nine of these and the five of the language model. The worker sends
  `job.run` and, of the model's, `ai.call`, `ai.tokens`, and `ai.refused`: it calls no tools, and
  the API says what the day has cost.

| Metric | What it says | Told apart by |
| --- | --- | --- |
| `health` | 1 while the backend is ready, 0 while it is not | nothing |
| `http.server.requests` | how many requests there were and how long each took | `outcome`, of which requests ordinarily have four |
| `jvm.heap.used` | the memory the backend's heap uses | nothing |
| `catalog.edit` | how long saving an edit of a working copy took | nothing |
| `catalog.copy` | how long copying a catalog into a new working copy took | nothing |
| `catalog.submit.refused` | how many submits were refused for the state the catalog was in | `reason`, of which there are three: `STALE`, `VEHICLE_LINE_INACTIVE`, `HAS_ERRORS` |
| `catalog.approved` | how many catalogs were approved | nothing |
| `catalog.rejected` | how many catalogs were rejected | nothing |
| `catalog.merged` | how many working copies were updated from Approved | nothing |
| `job.run` | how many jobs the worker handled and how long each took | `type`, of which there are four, and `outcome`: `SUCCESS` or `FAILURE` |
| `ai.call` | how many requests the language model was sent and how long each took | `purpose`, of which there are three: `RULE_SUGGESTION`, `SUBMISSION_SUMMARY`, `ANALYST`, and `outcome`: `SUCCESS` or `FAILURE` |
| `ai.tokens` | how many tokens the model was sent and answered with | `direction`: `in` or `out` |
| `ai.tool.calls` | how many tool calls the analyst made | nothing |
| `ai.refused` | how many requests to the model were refused before they were sent | `reason`, of which there are three: `DAILY_ALLOWANCE`, `ACCOUNT_ALLOWANCE`, `NO_KEY` |
| `ai.spent` | what asking the model has come to today, reserved and spent, in US dollars | nothing |
| `mem_used_percent` | the share of the host's memory in use | nothing |
| `disk_used_percent` | the share of the host's disk in use | nothing that varies |

CloudWatch counts a metric once for every value of what it is told apart by, which makes up to
thirty-seven of these, thirteen of them the language model's, and charges for each one beyond ten:
up to twenty-seven, at about $0.30 a month each, which is at most about $8 a month. So the backend
sends no metric that the alarm or the dashboard does not use: `Telemetry.java` in the backend
turns down every other. A metric is there once it has first been sent, so the two times and the
fourth outcome, a request that fails in the backend, appear when there has been one, and `job.run`
counts once for each type of job that has been done and once more for each that has failed. The
six that count catalogs are sent from the start, as zero in a minute when there was none.

The model's metrics are there once what they count has happened: a purpose that is never asked
for, an outcome or a refusal that never occurs, costs nothing. `ai.spent` is sent from the start
by an API that has a key for the model, and not at all by one that has none.

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
