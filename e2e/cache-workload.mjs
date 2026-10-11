// Measures what the cache gains: reads Approved versions as people do, on this machine, with the
// cache off, on and empty, on and holding what is read, and stopped, and prints the times and the
// queries PostgreSQL was sent as the tables of docs/cache-measurements.md.
//
//   npm run cache:measure     in e2e/, with nothing else running on the stack's ports
//
// It starts the browser tests' stack with the largest catalog, twice: without an address for the
// cache, and with one. It removes the stack and its data when it is done.
//
// It also says how even the times of such reads are, which the tables do not show:
//
//   npm run reads:even        sends reads of one seeded version for half a minute, without a cache
import { execFileSync } from "node:child_process";
import { setTimeout as sleep } from "node:timers/promises";
import { chromium } from "@playwright/test";

const site = "http://localhost:8092";
const SEQUENTIAL = 30;
const AT_ONCE = 8;
const ROUNDS_AT_ONCE = 3;
const UNTIMED = 5;
const HALF_A_MINUTE = 30_000;

const run = (command, args, env = {}) =>
  execFileSync(command, args, {
    env: { ...process.env, LARGEST_CATALOG: "true", ...env },
    encoding: "utf8",
    stdio: ["ignore", "pipe", "inherit"],
    maxBuffer: 64 * 1024 * 1024,
  });
const compose = (args, env) =>
  run(
    "docker",
    [
      "compose",
      "-p",
      "vehicle-catalog-e2e",
      "-f",
      "../compose.yaml",
      "-f",
      "compose.yaml",
      ...args,
    ],
    env,
  );
const container = (service) => compose(["ps", "--quiet", service]).trim();
const inTheCache = (...asked) => run("docker", ["exec", container("cache"), "redis-cli", ...asked]);
const inTheDatabase = (sql) =>
  run("docker", ["exec", container("db"), "psql", "-U", "catalog", "-d", "catalog", "-Atc", sql]);

/** Signs in as the demo author and answers with a function that sends a GET with that session. */
async function signedIn() {
  const browser = await chromium.launch();
  try {
    const context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(site);
    await page.getByRole("link", { name: "Sign in" }).click();
    await page.locator('input[name="username"]').fill("author");
    await page.locator('input[type="submit"]').click();
    await page.waitForURL(`${site}/dashboard`);
    const cookie = (await context.cookies()).map((c) => `${c.name}=${c.value}`).join("; ");
    return async (path) => {
      const answered = await fetch(site + path, { headers: { cookie } });
      const body = await answered.text();
      if (!answered.ok) throw new Error(`${path} answered ${answered.status}`);
      return JSON.parse(body);
    };
  } finally {
    await browser.close();
  }
}

/** The three kinds of request, by what they are called in the tables. */
async function requests(get) {
  const lineages = await get("/api/lineages");
  const of = (line, year) => lineages.find((l) => l.vehicleLine === line && l.modelYear === year);
  const suv = of("Compact SUV", 2026);
  const versions = await get(`/api/lineages/${suv.id}/versions`);
  const first = versions.find((version) => version.versionNumber === 1).catalogId;
  const largest = lineages.find((l) => l.vehicleLine === "Largest Catalog").catalogId;
  return {
    "Opening the largest Approved version": { path: `/api/catalogs/${largest}`, reads: [largest] },
    "Opening a seeded Approved version": {
      path: `/api/catalogs/${suv.catalogId}`,
      reads: [suv.catalogId],
    },
    "Comparing two versions of one lineage": {
      path: `/api/catalogs/${suv.catalogId}/diff?against=${first}`,
      reads: [suv.catalogId, first],
    },
  };
}

const rounded = (ms) => (ms < 10 ? ms.toFixed(1) : String(Math.round(ms)));
function summed(times) {
  const sorted = [...times].sort((a, b) => a - b);
  const at = (share) => sorted[Math.min(sorted.length - 1, Math.ceil(share * sorted.length) - 1)];
  return `median ${rounded(at(0.5))}, 95th percentile ${rounded(at(0.95))}, longest ${rounded(sorted.at(-1))}`;
}

/**
 * Times one kind of request: one after another, and then several at once. What is to be done
 * before each request, or before each round of several, is `before`: emptying the cache, say.
 */
async function timed(get, request, before = () => {}) {
  const once = async () => {
    const started = performance.now();
    await get(request.path);
    return performance.now() - started;
  };
  for (let i = 0; i < UNTIMED; i++) await once();
  const sequential = [];
  for (let i = 0; i < SEQUENTIAL; i++) {
    before();
    sequential.push(await once());
  }
  const atOnce = [];
  for (let round = 0; round < ROUNDS_AT_ONCE; round++) {
    before();
    atOnce.push(...(await Promise.all(Array.from({ length: AT_ONCE }, once))));
  }
  return { sequential: summed(sequential), atOnce: summed(atOnce) };
}

/**
 * What PostgreSQL was sent while `sending` ran: every statement but those that start and end a
 * transaction, and those of the two things the app does by the clock, which are sending the
 * messages of new jobs and ending sessions that have run out.
 */
async function statementsOf(sending) {
  inTheDatabase("ALTER SYSTEM SET log_statement = 'all'");
  inTheDatabase("SELECT pg_reload_conf()");
  await sleep(500);
  const since = new Date().toISOString();
  await sending();
  await sleep(500);
  // PostgreSQL writes its log where Docker keeps what a container says of errors.
  const logged = execFileSync(
    "sh",
    ["-c", 'docker logs --since "$0" "$1" 2>&1', since, container("db")],
    { encoding: "utf8", maxBuffer: 256 * 1024 * 1024 },
  );
  inTheDatabase("ALTER SYSTEM RESET log_statement");
  inTheDatabase("SELECT pg_reload_conf()");
  // A statement is logged with the lines it was written in, after a line that says when.
  const entry =
    /^\S+ \S+ UTC \[\d+\] LOG: {2}(?:execute [^:]+|statement): ([\s\S]*?)(?=^\d{4}-\d\d-\d\d )/gm;
  const sent = [...logged.matchAll(entry)]
    .map((match) => match[1].replace(/\s+/g, " ").trim())
    .filter((sql) => sql && !/^(BEGIN|COMMIT|ROLLBACK|SET |SHOW )/i.test(sql))
    .filter((sql) => !/\b(FROM outbox|SPRING_SESSION WHERE EXPIRY_TIME <)/i.test(sql));
  // To see which statements were counted, run with STATEMENTS=1.
  if (process.env.STATEMENTS) sent.forEach((sql) => console.error(`  ${sql.slice(0, 110)}`));
  return sent;
}
const readsCells = (sql) => /FROM catalog_cell\b/.test(sql) && /^SELECT feature_id/.test(sql);

async function measured(get, kinds, before) {
  const results = {};
  for (const [kind, request] of Object.entries(kinds)) {
    const times = await timed(get, request, () => before?.(request));
    before?.(request);
    const statements = await statementsOf(() => get(request.path));
    results[kind] = { ...times, statements: statements.length };
  }
  return results;
}

function table(title, columns, rows) {
  console.log(`\n### ${title}\n`);
  console.log(`| | ${columns.join(" | ")} |`);
  console.log(`|---|${columns.map(() => "---").join("|")}|`);
  for (const [name, cells] of Object.entries(rows))
    console.log(`| ${name} | ${cells.join(" | ")} |`);
}

const empty = (request) =>
  request.reads.forEach((catalog) => inTheCache("del", `approved-contents:1:${catalog}`));

/**
 * Sends reads of a seeded Approved version one after another for half a minute, without a cache,
 * and prints how even their times are: the median, how many took over twice as long, and the most
 * of those that came one after another.
 */
async function evenness() {
  console.error("Starting the stack without a cache...");
  run("npm", ["run", "stack:up"], { CACHE_HOST: "" });
  const get = await signedIn();
  const { path } = (await requests(get))["Opening a seeded Approved version"];
  for (let i = 0; i < UNTIMED; i++) await get(path);
  const times = [];
  const until = performance.now() + HALF_A_MINUTE;
  while (performance.now() < until) {
    const started = performance.now();
    await get(path);
    times.push(performance.now() - started);
  }
  const median = [...times].sort((a, b) => a - b)[Math.floor(times.length / 2)];
  let over = 0;
  let together = 0;
  let longest = 0;
  for (const time of times) {
    together = time > 2 * median ? together + 1 : 0;
    if (together) over++;
    longest = Math.max(longest, together);
  }
  console.log(
    `${times.length} reads of a seeded Approved version in 30 seconds, one after another,` +
      ` without a cache: median ${rounded(median)} ms, ${over} over twice the median,` +
      ` and at the most ${longest} of those one after another.`,
  );
}

async function tables() {
  console.error("Starting the stack without a cache...");
  run("npm", ["run", "stack:up"], { CACHE_HOST: "" });
  let get = await signedIn();
  let kinds = await requests(get);
  const off = await measured(get, kinds);

  console.error("Starting the API and the worker again, with the cache...");
  compose(["up", "-d", "--wait"]);
  get = await signedIn();
  kinds = await requests(get);
  const onAndEmpty = await measured(get, kinds, empty);
  const onAndHolding = await measured(get, kinds);

  const largest = kinds["Opening the largest Approved version"];
  empty(largest);
  const together = await statementsOf(() =>
    Promise.all(Array.from({ length: 20 }, () => get(largest.path))),
  );

  console.error("Stopping the cache...");
  compose(["stop", "cache"]);
  const stopped = await measured(get, kinds);
  compose(["start", "cache"]);

  const modes = { "Cache off": off, "On and empty": onAndEmpty, "On and holding it": onAndHolding };
  for (const [what, title] of [
    ["sequential", `One after another, ${SEQUENTIAL} requests`],
    ["atOnce", `${AT_ONCE} at once, ${ROUNDS_AT_ONCE} times`],
  ]) {
    table(
      title,
      [...Object.keys(modes), "Cache stopped"],
      Object.fromEntries(
        Object.keys(kinds).map((kind) => [
          kind,
          [...Object.values(modes), stopped].map((mode) => mode[kind][what]),
        ]),
      ),
    );
  }
  table(
    "Statements PostgreSQL was sent for one request",
    [...Object.keys(modes), "Cache stopped"],
    Object.fromEntries(
      Object.keys(kinds).map((kind) => [
        kind,
        [...Object.values(modes), stopped].map((mode) => mode[kind].statements),
      ]),
    ),
  );
  const asked = together.filter(readsCells).length;
  console.log(
    `\nTwenty reads of the largest version at once, with the cache holding nothing of it:` +
      ` PostgreSQL was asked for its cells ${asked === 1 ? "once" : `${asked} times`}.`,
  );
}

try {
  await (process.argv[2] === "evenness" ? evenness() : tables());
} finally {
  run("npm", ["run", "stack:down"]);
}
