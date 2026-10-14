// Times a person's list of twenty working copies, GET /api/catalogs?scope=mine, as the dashboard
// asks for it: fills the account's list with twenty copies of Sedan 2027's current Approved version,
// asks for the list thirty-five times, prints the times, and deletes the twenty again.
//
//   npm run list:measure                       against the browser tests' stack, as visitor
//   SITE=https://… PASSWORD=… npm run list:measure
//                                              against another site; ACCOUNT names the account,
//                                              visitor unless given, and PASSWORD its password
//   npm run list:measure -- look               prints the account's role and how many working
//                                              copies its list holds, and makes nothing
//   npm run list:measure -- delete <stamp>     deletes the twenty a run named with that stamp, and
//                                              no other, as after a run that ended before it could
//
// It signs in once in a browser and sends every request after that with the session's cookie. If
// the site's sign-in page cannot be driven, it opens a browser window for a person to sign in and
// goes on with that session. It prints no cookie, token, or password. If the list holds anything
// when it begins, it makes nothing and stops, so that nothing of anyone's is deleted to make room.
import { chromium } from "@playwright/test";

const site = (process.env.SITE || "http://localhost:8092").replace(/\/$/, "");
const account = process.env.ACCOUNT || "visitor";
const password = process.env.PASSWORD || "";
const COPIES = 20;
const UNTIMED = 4;
const TIMED = 30;
const MODEL_YEAR = 2027;
const VEHICLE_LINE = "SEDAN";
const nameOf = (stamp, n) => `List of twenty ${stamp} ${String(n).padStart(2, "0")}`;

/** The cookies the browser holds for the site, kept up to date from what the API sets. */
class Jar {
  constructor(cookies) {
    this.cookies = new Map(cookies.map((cookie) => [cookie.name, cookie.value]));
  }
  header() {
    return [...this.cookies].map(([name, value]) => `${name}=${value}`).join("; ");
  }
  take(response) {
    for (const line of response.headers.getSetCookie()) {
      const [pair] = line.split(";");
      const at = pair.indexOf("=");
      this.cookies.set(pair.slice(0, at).trim(), pair.slice(at + 1).trim());
    }
  }
  csrf() {
    const token = this.cookies.get("XSRF-TOKEN");
    if (!token) throw new Error("The session has no token against forged requests.");
    return token;
  }
}

/**
 * Drives the sign-in page: the username, the password where the page asks for one, and the
 * button. The browser tests' login server asks for a username alone; Amazon Cognito for both.
 */
async function driveSignIn(page) {
  await page.goto(site);
  await page.getByRole("link", { name: "Sign in" }).click();
  await page.waitForURL((url) => !url.href.startsWith(site), { timeout: 20_000 });
  const username = page
    .locator('input[name="username"], input[autocomplete="username"], input[type="email"]')
    .locator("visible=true")
    .first();
  await username.fill(account, { timeout: 20_000 });
  const passwordField = page.locator('input[type="password"]').locator("visible=true").first();
  if (await passwordField.isVisible().catch(() => false)) {
    if (!password) throw new Error("The page asks for a password, and PASSWORD is not set.");
    await passwordField.fill(password);
  }
  await page
    .locator('input[type="submit"], button[type="submit"]')
    .locator("visible=true")
    .first()
    .click();
  await page.waitForURL(`${site}/dashboard`, { timeout: 30_000 });
}

/**
 * Signs in and answers with the browser and a function that sends a request with the session.
 * When the sign-in page cannot be driven, a window is opened for a person to sign in.
 */
async function signedIn() {
  let browser = await chromium.launch();
  let context = await browser.newContext();
  try {
    await driveSignIn(await context.newPage());
  } catch (error) {
    console.log(`The sign-in page could not be driven (${error.message.split("\n")[0]}).`);
    await browser.close();
    browser = await chromium.launch({ headless: false });
    context = await browser.newContext();
    const page = await context.newPage();
    await page.goto(site);
    console.log("Sign in through the window that opened; this waits up to ten minutes.");
    await page.waitForURL(`${site}/dashboard`, { timeout: 600_000 });
  }
  const jar = new Jar(await context.cookies(site));
  const send = async (method, path, body, headers = {}) => {
    const response = await fetch(site + path, {
      method,
      headers: {
        cookie: jar.header(),
        ...(method === "GET" ? {} : { "X-XSRF-TOKEN": jar.csrf() }),
        ...(body === undefined ? {} : { "content-type": "application/json" }),
        ...headers,
      },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      redirect: "manual",
    });
    const text = await response.text();
    jar.take(response);
    if (!response.ok) throw new Error(`${method} ${path} answered ${response.status}: ${text}`);
    return text ? JSON.parse(text) : undefined;
  };
  const signOut = async () => {
    const { logoutUrl } = await send("POST", "/api/logout");
    // The provider's own session ends in the browser, where it is.
    await context
      .pages()[0]
      ?.goto(logoutUrl)
      .catch(() => {});
    await browser.close();
    console.log("Signed out.");
  };
  return { send, signOut };
}

const rounded = (ms) => (ms < 10 ? ms.toFixed(1) : String(Math.round(ms)));
function summed(times) {
  const sorted = [...times].sort((a, b) => a - b);
  const at = (share) => sorted[Math.min(sorted.length - 1, Math.ceil(share * sorted.length) - 1)];
  return `median ${rounded(at(0.5))}, 95th percentile ${rounded(at(0.95))}, longest ${rounded(sorted.at(-1))}`;
}

/** The list, timed from sending until the whole answer has arrived. */
async function list(send) {
  const started = performance.now();
  const copies = await send("GET", "/api/catalogs?scope=mine");
  return { copies, ms: performance.now() - started };
}

/** Says whether the list holds exactly the names a run made, and no other. */
function holdsExactly(copies, names) {
  const listed = copies.map((copy) => copy.name).sort();
  const made = [...names].sort();
  return listed.length === made.length && listed.every((name, i) => name === made[i]);
}

/**
 * Withdraws, if someone submitted it, and deletes every working copy of the account's that is a
 * run's: known by the ids the run was answered with, or, after a run that ended before it could
 * delete, by the names it printed.
 */
async function deleteOwn(send, isOwn) {
  const mine = (await send("GET", "/api/catalogs?scope=mine")).filter(isOwn);
  const failed = [];
  for (const copy of mine) {
    try {
      let revision = copy.revision;
      if (copy.status === "SUBMITTED") {
        revision = (await send("POST", `/api/catalogs/${copy.id}/withdraw`)).revision;
      }
      await send("DELETE", `/api/catalogs/${copy.id}`, undefined, { "If-Match": `"${revision}"` });
    } catch (error) {
      failed.push(`${copy.name}: ${error.message.split("\n")[0]}`);
    }
  }
  console.log(
    `Deleted ${mine.length - failed.length} of the run's ${mine.length} that were there.`,
  );
  if (failed.length) throw new Error(`Not deleted:\n  ${failed.join("\n  ")}`);
}

/** Makes the twenty, asks for the list thirty-five times, and deletes them, whatever happened. */
async function measured(send) {
  const line = (await send("GET", "/api/vehicle-lines")).find((l) => l.code === VEHICLE_LINE);
  const start = await send(
    "GET",
    `/api/catalogs/start-point?vehicleLineId=${line.id}&modelYear=${MODEL_YEAR}`,
  );
  if (start.kind !== "COPY") {
    throw new Error(`${line.name} ${MODEL_YEAR} has no current Approved version to copy.`);
  }
  const stamp = new Date().toISOString().replace(/\.\d+Z$/, "Z");
  const names = Array.from({ length: COPIES }, (_, i) => nameOf(stamp, i + 1));
  console.log(
    `Making ${COPIES} working copies of ${line.name} ${MODEL_YEAR} v${start.versionNumber}` +
      ` (catalog ${start.catalogId}), named "${names[0]}" to "${names.at(-1)}".`,
  );
  console.log(`To delete them alone: npm run list:measure -- delete ${stamp}`);
  const ids = [];
  let fullFrom;
  try {
    for (const name of names) {
      const made = await send("POST", "/api/catalogs", {
        name,
        vehicleLineId: line.id,
        modelYear: MODEL_YEAR,
      });
      ids.push(made.id);
    }
    fullFrom = new Date();
    const ask = async () => {
      const answer = await list(send);
      if (!holdsExactly(answer.copies, names)) {
        throw new Error(
          `An answer held ${answer.copies.length} working copies, not the ${COPIES} this run made:` +
            ` ${answer.copies.map((copy) => `"${copy.name}"`).join(", ")}`,
        );
      }
      return answer.ms;
    };
    console.log(`The first ask after the copies were made: ${rounded(await ask())} ms.`);
    for (let i = 0; i < UNTIMED; i++) await ask();
    const timed = [];
    for (let i = 0; i < TIMED; i++) timed.push(await ask());
    console.log(
      `${TIMED} asks one after another, each timed from sending until the whole answer had` +
        ` arrived, in ms: ${summed(timed)}.`,
    );
    console.log(`Each of the ${timed.length}: ${timed.map(rounded).join(", ")}.`);
  } finally {
    const fullUntil = new Date();
    if (fullFrom) {
      console.log(
        `The list was full from ${fullFrom.toISOString()} until ${fullUntil.toISOString()}.`,
      );
    }
    await deleteOwn(send, (copy) => ids.includes(copy.id));
  }
}

const [mode, stamp] = process.argv.slice(2);
if (!["look", "delete", undefined].includes(mode) || (mode === "delete") !== Boolean(stamp)) {
  console.error("Usage: npm run list:measure [-- look | -- delete <stamp>]");
  process.exit(2);
}
const { send, signOut } = await signedIn();
try {
  const me = await send("GET", "/api/me");
  const { copies } = await list(send);
  console.log(
    `Signed in with roles ${me.roles.join(", ").toLowerCase() || "none"};` +
      ` the list holds ${copies.length} working cop${copies.length === 1 ? "y" : "ies"}.`,
  );
  if (mode === "delete") {
    const names = Array.from({ length: COPIES }, (_, i) => nameOf(stamp, i + 1));
    await deleteOwn(send, (copy) => names.includes(copy.name));
  } else if (mode === "look") {
    // Nothing more: the role and the count were what was asked.
  } else if (copies.length > 0) {
    console.log("The list is not empty, so nothing is made: the twenty of a run are all its own.");
  } else {
    await measured(send);
  }
} finally {
  await signOut();
}
