# A backup put back on a clean machine

How long it takes to put a backup of the database back on a machine that holds nothing else of the app's and to start the API on it, how it is known that nothing was missing, and what a restore loses. On 2026-10-14 a backup made by hand on the host, and the night's backup, were each put back into an empty database on the clean machine that `infra/restore.tf` describes, and the API of the commit the host ran was started on the first. From the machine being asked for to a catalog being read took 7 minutes 3 seconds. Every table put back held the rows the host held when the backup was made, and the API took a session the backup held. [Putting a backup back on a clean machine](../infra/README.md#putting-a-backup-back-on-a-clean-machine) says how it is done.

Five things limit what it says:

- **The API ran alone.** It was asked for its readiness, its health, and three reads, with a session read out of the restored database. Nobody signed in there. No worker, queue, bucket, or cache was part of it, and the API was told of none.
- **Nothing was put back over the live database.** The steps for that are run on the check's stack alone, by `deploy/test/check.sh`.
- **It is one reading,** of the seeded demo's size: a backup of 142,813 bytes and 37 tables. Nothing was repeated.
- **The machine had no processor time saved.** It ran at its baseline through its set-up and the whole restore, as [the picture](#the-machines-processor) shows. A machine with processor time saved is taken to set itself up and start the API faster; that was not measured.
- **Some of the 7 minutes 3 seconds are the restore's own checks.** The 29 seconds between the set-up's end and the restore's start went to the look at the guard against a forgotten machine, and 16 seconds after the API was ready went to the command of the checks after it, which looked at the image, asked for a token from inside the API's container with a timeout of five seconds, and then read the catalog.

## Results

All times are in UTC, on 2026-10-14.

### The bucket's backups

The bucket held a backup for every night from 2026-10-09 on. The rows to 2026-10-13 are from `aws s3 ls` at 00:48, and the rest from the same listing made just after the backup by hand at 17:50:

| Backup | Bytes | Made |
|---|---:|---|
| `catalog-2026-10-09T0230Z.dump` | 63,318 | each night, by the timer |
| `catalog-2026-10-10T0230Z.dump` | 104,283 | each night, by the timer |
| `catalog-2026-10-10T1522Z.dump` | 105,632 | by hand |
| `catalog-2026-10-11T0230Z.dump` | 132,762 | each night, by the timer |
| `catalog-2026-10-12T0230Z.dump` | 135,160 | each night, by the timer |
| `catalog-2026-10-13T0230Z.dump` | 136,669 | each night, by the timer |
| `catalog-2026-10-14T0230Z.dump` | 139,475 | each night, by the timer |
| `catalog-2026-10-14T0418Z.dump` | 138,183 | by hand, when the alarm on a night without a backup was tried ([the dashboard and its alarms](dashboard-and-alarms.md)) |
| `catalog-2026-10-14T1750Z.dump` | 142,813 | by hand, for this restore |

At 00:48 the bucket also held `catalog-2026-10-08T1857Z.dump`, of 65,868 bytes, made by hand before the timer's first night. No night from 2026-10-09 on is missing. Each night's run by the journal, to 2026-10-13, took one or two seconds in whole seconds, and the one by hand for this restore ran from 17:50:29 to 17:50:31, with `Result=success`.

While a backup is made, the host keeps the copy in its memory and not on its disk. `deploy/backup.sh` writes `pg_dump`'s output to a file `mktemp` makes, the unit sets no `TMPDIR` and no private `/tmp`, and the host's `/tmp` is a `tmpfs` of 921 MiB. The host has 1,840 MB of memory and no swap.

### The timeline

| | |
|---|---|
| 17:44:04 | The host's API starts, released for commit `c49bb41`. It is not started again until after the restore. |
| 17:48:33 | `visitor` is an author and has no working copy. |
| 17:50:23 | `visitor` makes the working copy "Restore sitting: made before the backup", from Sedan 2027 v1, and leaves its session signed in. |
| 17:50:28.8 to 17:50:29.1 | Every table is counted on the host. |
| 17:50:29.1 to 17:50:31.1 | The backup is made by hand, `catalog-2026-10-14T1750Z.dump`, of 142,813 bytes. |
| 17:50:31.1 to 17:50:31.3 | Every table is counted on the host again. |
| 17:50:43 | `visitor` makes "Restore sitting: made after the backup", and signs out. |
| 17:50:58 | The plan that makes the machine is saved and read: 13 to add, nothing else. |
| 17:51:11 | The machine is asked for: the apply begins. |
| 17:51:25 | The machine is launched. It boots at 17:51:32. |
| 17:51:43 | The apply ends, 13 added. |
| 17:52:04 | The machine is first asked after: it is online to Systems Manager, its set-up queued. |
| 17:52:10 to 17:54:48 | The machine sets itself up, and succeeds. |
| 17:55:03 to 17:55:08 | The guard against a forgotten machine is looked at. |
| 17:55:17 | The restore of the backup made by hand begins. |
| 17:57:58 | The API is ready on it. |
| 17:58:13.6 | `visitor`'s list is read with a session from the restored database. |
| 17:58:14.0 | A seeded Approved version is read with it. |
| 17:58:26 to 17:58:31 | The night's backup is put back into a second empty database, without an API. |
| 17:59:09 to 17:59:46 | The pipeline's plan role plans with the machine there, and finds no change. |
| 18:05:59 | The plan that removes the machine is saved and read: 13 to destroy, nothing else. |
| 18:06:18 to 18:06:54 | The machine and its resources are removed. |
| 18:07:23 | The two working copies are deleted. `visitor`'s list is empty again. |

### How long each step took

| Step | Seconds |
|---|---:|
| The machine asked for, until it was launched | 14 |
| Launched, until the apply ended | 18 |
| Launched, until it was online to Systems Manager | at most 39 |
| The set-up | 158 |
| The empty database started, with the database's image fetched | 43.9 |
| The backup fetched | 1.9 |
| The backup put back | 0.9 |
| What it holds counted | 0.9 |
| The API's image fetched | 20.0 |
| The API started and ready | 92.4 |
| The restore by the script, in whole | 160.1 |
| The machine asked for, until a catalog was read | 423 |

The set-up does not fetch the database's image, so the script fetches it before it starts the empty database, and that is taken to be most of its 43.9 seconds: the two were not timed apart. The night's backup, put back afterwards on the same machine with the image there, had its empty database in 2.9 seconds, was fetched in 1.0, put back in 0.5, and counted in 0.4: 4.7 seconds in whole. The API's own log says "Started VehicleCatalogApplication in 82.95 seconds", about four times the 20 seconds the host's API takes ([the decision on the API's heap](adr/0018-the-apis-heap-is-limited-to-256-mib-chosen-from-reads-of-the-largest-catalog.md)).

### The machine's processor

![The clean machine's CPU utilization, the most in each minute, from 17:50 to 18:04: no line until 17:52, then about 0, flat at about 19.8 percent from 17:53 to 17:57 against a line at the 20 percent baseline, 15 at 17:58, 3 at 17:59, and under 1 after, with the restore's steps marked](restore-clean-machine-processor.png)

The line is the most the machine's processor did in each minute. It stayed between 19.70 and 19.88 percent from 17:53 to 17:57, through the set-up and the restore, which is the baseline a machine of its kind is held to once it has no processor time saved. It fell to 15.26 at 17:58, 3.09 at 17:59, and under 1 after. So the machine ran slowed through the whole of its set-up and the restore.

Its saved processor time, `CPUCreditBalance`, and what it spent, `CPUCreditUsage`, are read by CloudWatch every five minutes. Each reading below is of the five minutes from the time it is stamped with:

| Five minutes from | Credits saved | Credits spent |
|---|---:|---:|
| 17:55, the restore's start | 0.018 | 1.449 |
| 18:00, after the restore's end at 17:58:31 | 0.670 | 1.347 |
| 18:05 | 2.600 | 0.071 |

The host had 576, the most its kind keeps, when [its memory was measured](host-memory-measurements.md).

### The rows of every table

The host was counted before and after the backup by hand, and the backup was counted where it was put back. The night's backup, made at 02:30, is beside them:

| Table | Host before | Host after | Put back | The night's backup |
|---|---:|---:|---:|---:|
| `ai_spend` | 92 | 92 | 92 | 88 |
| `app_user` | 5 | 5 | 5 | 5 |
| `approved_check` | 4 | 4 | 4 | 4 |
| `catalog` | 7 | 7 | 7 | 6 |
| `catalog_cell` | 6245 | 6245 | 6245 | 5175 |
| `catalog_change` | 9 | 9 | 9 | 9 |
| `catalog_export` | 0 | 0 | 0 | 0 |
| `catalog_feature` | 1137 | 1137 | 1137 | 965 |
| `catalog_region` | 18 | 18 | 18 | 15 |
| `catalog_review` | 0 | 0 | 0 | 0 |
| `catalog_rule` | 53 | 53 | 53 | 46 |
| `catalog_rule_region` | 7 | 7 | 7 | 6 |
| `catalog_rule_target` | 61 | 61 | 61 | 53 |
| `catalog_rule_trim` | 7 | 7 | 7 | 6 |
| `catalog_trim` | 28 | 28 | 28 | 24 |
| `catalog_trim_region` | 59 | 59 | 59 | 49 |
| `category` | 13 | 13 | 13 | 13 |
| `document` | 3 | 3 | 3 | 3 |
| `document_passage` | 3 | 3 | 3 | 3 |
| `feature` | 330 | 330 | 330 | 330 |
| `flyway_schema_history` | 21 | 21 | 21 | 20 |
| `global_rule` | 14 | 14 | 14 | 14 |
| `global_rule_region` | 1 | 1 | 1 | 1 |
| `global_rule_target` | 18 | 18 | 18 | 18 |
| `job` | 8 | 8 | 8 | 10 |
| `library_state` | 1 | 1 | 1 | 1 |
| `lineage` | 4 | 4 | 4 | 4 |
| `notification` | 0 | 0 | 0 | 0 |
| `outbox` | 8 | 8 | 8 | 10 |
| `region` | 4 | 4 | 4 | 4 |
| `spring_session` | 1 | 1 | 1 | 3 |
| `spring_session_attributes` | 1 | 1 | 1 | 3 |
| `submission_summary` | 1 | 1 | 1 | 1 |
| `trim` | 6 | 6 | 6 | 6 |
| `vehicle_line` | 5 | 5 | 5 | 5 |
| `vehicle_type` | 4 | 4 | 4 | 4 |
| `working_copy_count` | 1 | 1 | 1 | not there |

The two counts on the host are the same for every table, and so is the backup put back: no table lies outside them, so none needs explaining. The night's backup has 36 tables. `working_copy_count` came with migration 19, released at 16:11, after it was made.

The migrations' history put back holds versions 1 to 19, the highest 19, every one succeeded, and two rows for the repeatable `R__copy_catalog.sql`: 21 rows. The image released holds `V1` to `V19`. The night's backup holds versions 1 to 18 and the same two rows. Both have the `vector` extension, 0.8.7.

### What the API answered

The API ran from the image `sha256:265b2157…`, which is the id of `vehicle-catalog-backend:current` on the host, for commit `c49bb41`, the newest `Deploy` that succeeded. It answered its readiness with 200, and its health said `UP`, with its database `UP` and its cache `UNKNOWN`, since it has none.

The restored database held one session of `visitor`'s, last used at 17:50:23, when the first working copy was made. Asked with it, from inside one command that never printed it:

- `visitor`'s list answered 200, and named "Restore sitting: made before the backup" and nothing else.
- Sedan 2027 v1, the seeded Approved version, answered 200, `APPROVED`, an answer of 95,833 characters.
- Without a cookie, the list answered 401.
- A third read, of the same version with the same session, sent with the night's restore, gave its name, "Launch content".

By SQL, the restored database holds the first working copy and not the second. The night's backup holds neither.

## What it shows

- **The restore is whole.** `pg_restore` ended with 0 for both backups. Every table put back from the backup made by hand has the host's rows, the migrations are those of the image released, and the API started on it, took a session it held, and read a catalog.
- **What the restore kept and what it lost.** The backup made by hand kept the working copy made about five seconds before it began, and lost the one made twelve seconds after it ended. The night's backup, the newest one the timer had made, was 15 hours 20 minutes behind at 17:50, and lacked both, 4 rows of the language model's spending, and migration 19. Its catalogs' tables differ from the backup made by hand as well; `catalog_cell` by 1,070 rows and `catalog_feature` by 172, which are Sedan 2027 v1's stored cells and feature rows as [the record of the list of working copies](working-copy-list-measurements.md) has them, so those rows are taken to be the first working copy's. The other catalogs' tables were not taken apart.
- **The night's backup held what has since gone.** It has 3 sessions where the host had 1 at 17:50, and 10 jobs and their 10 messages for the queue where the host had 8 of each; the 2 messages more are those of the 2 jobs more, since a message is kept once it is sent and goes only with its job. Sessions end by the clock, and the demo reset runs at 03:00, half an hour after the backup. Which of the two took those rows away was not looked at: the host's logs for those hours were not read.
- **The checks ticket #64 left unmet are met.** "A backup appears in the bucket after the nightly run": one appeared for every night from 2026-10-09 to 2026-10-14, and the night's of 2026-10-14 was put back with `pg_restore` ending with 0. "The app starts on a restored database": the API of the commit the host ran was ready on the backup made by hand at 17:57:58, and answered with its data.

## What no backup holds

The files of uploaded documents, which are in the bucket `rgonz-vehicle-catalog-documents`; the exported spreadsheets, in `rgonz-vehicle-catalog-exports`; the messages on the queue of the jobs; and the host's certificate, which Caddy keeps in a volume of its own. A restore brings none of them back. A backup does hold the email addresses, the login provider's subjects, and the sessions of everyone who signed in, which is why it was fetched on the clean machine alone, onto the disk `infra/restore.tf` encrypts and deletes with the machine, and reached no workstation.

## What was tried beside the restore

- **The shutdown that ends a forgotten machine.** At 17:55:05, on the machine, `/run/systemd/shutdown/scheduled` held a power-off for 20:51:39, three hours and seven seconds after its boot at 17:51:32. `describe-instance-attribute` gave its shutdown behaviour as `terminate`, and `describe-instances` its disk as deleted with it, its hop limit as 1, and its metadata tokens as required. The machine was removed at 18:06, so the shutdown never ran.
- **Whether a container gets the role's credentials.** From the machine itself, the metadata address gave a token of 56 characters, whose value was not printed. From inside the API's container, Java's HTTP client asked the same address for a token and got none: it gave up with `HttpTimeoutException` after 5,021 ms. A hop limit of 1 keeps the role's credentials from a container.
- **Whether the pipeline's plan role reads the machine.** With the machine there, a plan from the workstation with the switch on said "No changes". A draft pull request whose one change turned the switch on by default, titled as never to be merged, had its `plan` check run as the plan role from 17:59:09 to 17:59:46. Its comment says "No changes. Your infrastructure matches the configuration.", and its log read every one of the machine's 13 resources and was refused nothing. So the plan role's policy needs no read added. The pull request was closed unmerged and never marked ready, its branch was deleted, and the `Plan` run, whose log named the machine's resources, was deleted at 18:00:45.

After the removal, `terraform plan` said "No changes", no machine tagged `vehicle-catalog-restore` was running or pending, and the role and the instance profile were gone. The plan that removed the machine named its 13 resources and nothing else. The host's API had the start time of 17:44:04 before and after.

## How it was done

The two working copies were made, and deleted by their ids at the end, by a script that signs in as `visitor` on the deployed app in a headless browser, through the app's own sign-in page, as `e2e/working-copy-list.mjs` does, and sends the requests with the session it gets.

On the host, through Systems Manager, as one command, the counts and the backup by hand:

```sh
cd /opt/vehicle-catalog
count() { docker compose exec -T db psql --username catalog --dbname catalog --tuples-only --no-align --field-separator ' ' --quiet <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I', relname, relname) FROM pg_class JOIN pg_namespace ON pg_namespace.oid = relnamespace WHERE relkind = 'r' AND nspname = 'public' ORDER BY relname \gexec
SQL
}
count
systemctl start vehicle-catalog-backup.service
count
journalctl --unit vehicle-catalog-backup.service --no-hostname -n 4 --no-pager -o short-iso
systemctl show vehicle-catalog-backup.service -p ExecMainStartTimestamp -p ExecMainExitTimestamp -p Result
```

The machine was made and removed as [the README](../infra/README.md#putting-a-backup-back-on-a-clean-machine) says, with each plan saved, read, and deleted after its apply. The plan that made it, saved at 17:50:58 with the switch on on the command line, ended with `Plan: 13 to add, 0 to change, 0 to destroy.`, and the one that removed it, saved at 18:05:59, with `Plan: 0 to add, 0 to change, 13 to destroy.` Each named these 13 resources and no other:

- `aws_instance.restore[0]` and `aws_ssm_association.restore_setup[0]`;
- `aws_security_group.restore[0]`, `aws_vpc_security_group_egress_rule.restore[0]`, and `aws_vpc_security_group_rules_exclusive.restore[0]`;
- `aws_iam_role.restore[0]`, `aws_iam_instance_profile.restore[0]`, `aws_iam_role_policy_attachment.restore_systems_manager[0]`, `aws_iam_role_policy.restore_fetch_backups[0]`, `aws_iam_role_policy.restore_fetch_backend[0]`, `aws_iam_role_policy.restore_no_parameters[0]`, `aws_iam_role_policies_exclusive.restore[0]`, and `aws_iam_role_policy_attachments_exclusive.restore[0]`.

The machine's set-up was waited for by asking `aws ssm describe-instance-associations-status`, with ten seconds between asks, until it said `Success`. On the machine, through Systems Manager, the two restores were:

```sh
/opt/vehicle-catalog-restore/put-back.sh catalog-2026-10-14T1750Z.dump c49bb41856601ed34440f0633ed7ce3c9a30ea5c
/opt/vehicle-catalog-restore/put-back.sh catalog-2026-10-14T0230Z.dump
```

Then, on the machine, `docker inspect --format '{{.Image}}'` on the API's container for its image, `curl` for its readiness and its health, the list and the Approved version asked with the session as the README has it, and a short Java program run with `java` inside the API's container that asks the instance metadata service for a token, with `PUT` and a timeout of five seconds. The host's image id was read with `docker image inspect --format '{{.Id}}' vehicle-catalog-backend:current`, and its API's start time with `docker inspect --format '{{.State.StartedAt}}'`.

No command fetched a backup anywhere but on the clean machine. What each command printed, as `aws ssm get-command-invocation` gives it, was 2,286 characters for the counts and the backup, 596 for the guard, 1,988 for the restore by hand, 2,141 for the checks after it, and 1,729 for the night's restore: none near the 24,000 Systems Manager keeps, so none was cut short. A search of them for `@`, for a 12-digit number, and for a UUID found nothing.

The picture was drawn by this command, with the machine's id in `$machine`, read from `aws ec2 describe-instances` by its tag:

```sh
aws cloudwatch get-metric-widget-image --output-format png --query MetricWidgetImage --output text --metric-widget '{
  "title": "The clean machine'"'"'s processor during the restore, 2026-10-14 (UTC)", "width": 1000, "height": 420, "timezone": "+0000",
  "start": "2026-10-14T17:50:00Z", "end": "2026-10-14T18:04:00Z", "period": 60, "stat": "Maximum", "view": "timeSeries", "region": "us-east-1",
  "yAxis": {"left": {"min": 0, "max": 100, "label": "Percent", "showUnits": false}},
  "metrics": [["AWS/EC2", "CPUUtilization", "InstanceId", "'"$machine"'", {"label": "Clean machine, CPU utilization (maximum per minute)", "color": "#1f77b4"}]],
  "annotations": {
    "vertical": [
      {"label": "Machine started", "value": "2026-10-14T17:51:32Z"}, {"label": "Set-up", "value": "2026-10-14T17:52:10Z"},
      {"label": "Set up", "value": "2026-10-14T17:54:48Z"}, {"label": "Empty database", "value": "2026-10-14T17:55:17Z"},
      {"label": "Put back", "value": "2026-10-14T17:56:03Z"}, {"label": "API starting", "value": "2026-10-14T17:56:26Z"},
      {"label": "API ready", "value": "2026-10-14T17:57:58Z"}, {"label": "Nightly put back", "value": "2026-10-14T17:58:26Z"}],
    "horizontal": [{"label": "Baseline 20%", "value": 20}]}}' | base64 -d > restore-clean-machine-processor.png
```

The credits were read with `aws cloudwatch get-metric-statistics` for `CPUCreditBalance` and `CPUCreditUsage` of the same machine, from 17:50 to 18:10. The picture was looked at before it was committed: it shows times and percentages and no id, account, or address.

## When, and on what

- 2026-10-14, from 17:47 to 18:07 UTC, with the application of commit `c49bb41` on the host and on the clean machine.
- The clean machine is described by `infra/restore.tf` with the host's image, size, disk, and "standard" processor credits: a `t4g.small`, on which `nproc` gave 2 and `free -m` 1,840 MB in all. Docker 25.0.14 and the AWS CLI 2.36.47 were on it after its set-up.
- The database ran from `pgvector/pgvector:0.8.7-pg18-trixie`, the image the host runs its own from.
