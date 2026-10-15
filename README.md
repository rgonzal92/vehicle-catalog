# Vehicle Catalog

An app for writing vehicle catalogs: which features each trim of a vehicle line offers in each
region for a model year, the rules that relate those features, and the review that turns a working
copy into an Approved version. A language model helps in three places, and in each of them the
application checks what the model says and a person decides.

**Live demo: [catalog.rgonz.dev](https://catalog.rgonz.dev).** The demo accounts, one per role, are
on its landing page. The demo reset at 03:00 UTC each day deletes everything visitors did.

## What it does

- **The matrix.** An author edits a working copy as feature rows set against offerings, an offering
  being one trim sold in one region. Each cell is Standard, Available, or Not offered.
- **Rules and validation.** Rules (Requires, Requires one of, Excludes, Includes) relate features,
  either for every catalog from the library or within one catalog. Every edit validates the whole
  catalog: an Error blocks submit and approve, a Warning never does.
- **Review.** A manager approves or rejects a submitted working copy, and approval makes an
  immutable Approved version. A working copy whose base is no longer the current Approved is stale,
  and an update from Approved brings the newer changes in, asking the owner to settle each conflict.
  Carryover starts a new model year from the year before.
- **The library.** Admins maintain the trims, regions, and features that catalogs add, the global
  rules, and people's roles.
- **The language model.** It proposes rules from a sentence (suggestions), writes a summary of what
  a submission changes for its reviewer, and answers questions about the Approved catalogs on the
  Analyst page, citing the documents admins upload. It never writes to a catalog: Java checks what
  it proposes, and a person saves it.
- **Exports and notifications.** A catalog exports as a spreadsheet, and people are told in the app
  what happened to their work. Both are jobs that a worker does after the change.

## How it is built

| Part           | Built with                                                                            |
| -------------- | ------------------------------------------------------------------------------------- |
| Frontend       | Angular 22, PrimeNG, Tailwind CSS                                                     |
| Backend        | Java 25, Spring Boot 4, Spring AI (OpenAI), Flyway, Spring Session                    |
| Data           | PostgreSQL 18 with pgvector, Redis as a cache                                         |
| Jobs           | Amazon SQS, read by a worker that is the backend's own build                          |
| Infrastructure | AWS (CloudFront, S3, one EC2 `t4g.small`, Cognito, CloudWatch, X-Ray, SNS), Terraform |
| Tests          | JUnit with Testcontainers, Vitest, Playwright with axe                                |

CloudFront serves the Angular build from a private S3 bucket and sends everything under `/api` to
the host. On the host, Caddy accepts only requests that come through CloudFront and passes them to
the API; the worker, PostgreSQL, and Redis run beside it in one Compose stack, kept in
[`deploy/`](deploy/). People sign in through Amazon Cognito. A change merged to `main` that passes
every check is built into an image and released to the host by the pipeline.
[`infra/README.md`](infra/README.md) says what each part in AWS is for, how a change reaches it, and
what to do when an alarm goes off.

### Decisions

Each design decision is recorded in [`docs/adr/`](docs/adr/). A few to start with:

- [The model proposes, Java checks, and a person saves](docs/adr/0013-the-model-proposes-java-checks-and-a-person-saves.md)
- [What the model may cost is reserved in the database before it is asked](docs/adr/0014-what-the-model-may-cost-is-reserved-in-the-database-before-it-is-asked.md)
- [Documents are searched in PostgreSQL by what their passages mean](docs/adr/0015-documents-are-searched-in-postgresql-by-what-their-passages-mean.md)
- [The contents of Approved versions are read from a cache that is never needed](docs/adr/0016-the-contents-of-approved-versions-are-read-from-a-cache-that-is-never-needed.md)
- [The host is a t4g.small, chosen from its measured memory](docs/adr/0009-the-host-is-a-t4g-small-chosen-from-its-measured-memory.md)

### Measurements

What was measured, how, and what it showed:

- [Measurements at the largest catalog size](docs/largest-catalog-measurements.md): 500 feature rows
  by 96 offerings, with 500 rules.
- [Matrix measurements at the largest catalog size](docs/matrix-measurements.md): how the matrix
  scrolls and edits at that size.
- [What the cache gains](docs/cache-measurements.md)
- [How long a person's list of working copies takes](docs/working-copy-list-measurements.md)
- [Memory the host's stack uses](docs/host-memory-measurements.md)
- [A backup put back on a clean machine](docs/restore-measurements.md)
- [How well the language model does what it is asked](docs/ai-evaluation.md)
- [The dashboard and its alarms](docs/dashboard-and-alarms.md)

## Run it locally

It needs Docker with Compose, and Node.js 24.

1. Run `cp .env.example .env`, and set `CATALOG_DB_PASSWORD` in `.env` to a password of your own.
2. Run `docker compose up --build -d`. It starts the database, the API, the worker, the cache, and
   local stand-ins for Cognito, SQS, and S3. The API answers on `127.0.0.1:8090`.
3. In `frontend/`, run `npm ci`, then `npm start`, and open http://localhost:4200.
4. Sign in with a username alone: `author`, `manager`, or `admin` for the demo accounts, or
   `visitor` for a sandbox account.

The local stack runs without a language model: it is given no `OPENAI_API_KEY`, so the model cannot
be asked. As on the demo, the seeded data is put back at 03:00 UTC each day; set
`DEMO_RESET_CRON=-` to keep what you did.

## Run the checks

These are the commands the Verify workflow runs on every pull request.

Backend tests and the format check. They need JDK 25, and Docker for Testcontainers:

```sh
cd backend
./mvnw -B verify
```

Frontend:

```sh
cd frontend
npm ci
npm run format:check && npm run lint && npm test && npm run build
```

Browser tests. They build the frontend, start a stack of their own on port 8092 with the password
from `.env`, run, and remove the stack:

```sh
cd e2e
npm ci --prefix ../frontend && npm ci
npx playwright install chromium
npm run format:check && npm run lint && npm run typecheck && npm run e2e
```

Infrastructure:

```sh
cd infra
terraform fmt -check -recursive
terraform init -backend=false -input=false && terraform validate
terraform test
```

The pipeline has three workflows in [`.github/workflows/`](.github/workflows/):

- **Verify**: the checks above, and a check of the host's stack, on every pull request and on
  `main`.
- **Plan**: `terraform plan` with a read-only role, posted on the pull request.
- **Deploy**: after Verify passes on `main`, builds the backend's image, releases it to the host,
  and publishes the frontend.

## Repository layout

- [`backend/`](backend/): the Spring Boot API, and the worker, which is the same build.
- [`frontend/`](frontend/): the Angular app.
- [`e2e/`](e2e/): the Playwright browser tests.
- [`infra/`](infra/): Terraform for everything in AWS.
- [`deploy/`](deploy/): the stack that runs on the host, and its release, backup, and restore
  scripts.
- [`docs/`](docs/): decision records, measurements, and pictures of the dashboard.
- [`GLOSSARY.md`](GLOSSARY.md): the words the code, tickets, and docs use, and what each means.

## License

[MIT](LICENSE). The Inter font in `frontend/public/fonts/` keeps its own license, the SIL Open Font
License 1.1.
