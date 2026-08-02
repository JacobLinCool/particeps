/**
 * Drives the researcher page the way a person would and checks that what falls out the end is a
 * study file the Android app accepts.
 *
 * This is separate from `pnpm test` because it needs a build, a server, and a browser. It exists
 * because the unit tests prove the library and prove nothing about the page: the first run of this
 * script found a collector card marked `aria-disabled` while switched off, which made the only
 * control that could switch it on unavailable to assistive technology — invisible to every other
 * check in the project.
 *
 * Two properties are checked at the seam where only a browser can check them:
 *
 *   1. What the page *showed* is what the file *says*. The identifiers are derived now rather than
 *      typed, so the sign step's readout is a claim the page makes, and the CLI printing the same
 *      two names back is the only thing that tests it. Same for the fingerprint on the files step:
 *      it is what goes into a recruitment sheet, and `check-config` recomputes it from the file.
 *   2. The bytes are Gson's bytes. The study text below is deliberately hostile — an em dash, CJK,
 *      an emoji, a quote, a backslash, a newline, and characters Gson leaves alone — and the
 *      canonical JSON the page hands over is fed back through `researcher-tools canonicalize`,
 *      which must return it unchanged. An encoder that "helpfully" escapes `<` or `&`, or that
 *      emits `—` for the em dash, fails here and only here.
 *
 *   pnpm build && pnpm exec http-server build -p 4173   # or any static server
 *   node e2e/researcher-flow.mjs
 */
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, existsSync, readFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:4173';
const CLI = join(
  import.meta.dirname,
  '../../researcher-tools/build/install/researcher-tools/bin/researcher-tools'
);

/** `[a-z0-9][a-z0-9-]{2,63}`, from `lib/adc/types.ts`. Both identifiers have to satisfy it. */
const ID_PATTERN = /^[a-z0-9][a-z0-9-]{2,63}$/;

/**
 * The study text. Every hostile character has a job: the em dash, the CJK and the emoji are
 * non-ASCII that Gson emits raw; the quote and the backslash are the two characters it escapes; the
 * newline is the one control character in ordinary prose; and `&` and `<ok>` are what an HTML-safe
 * writer would mangle. The title is also the source of `experiment_id`, so it exercises the slug
 * path with text that mostly cannot appear in a slug.
 */
const TITLE = 'Browser-authored study — 瀏覽器建立 🔬';
const RESEARCHER = 'E2E Lab "Verification" \\ Group';
const CONSENT =
  'Collects motion and app activity.\nStays on the phone until exported. 正體中文 & <ok> = fine.';
/** `asciiSlug(TITLE)`. The page must derive exactly this, and the CLI must print it back. */
const EXPECTED_EXPERIMENT_ID = 'browser-authored-study';
/** One of `PRESETS.duration_hours`, clicked rather than typed. */
const DURATION_HOURS = 168;

const out = mkdtempSync(join(tmpdir(), 'adc-e2e-'));
const browser = await chromium.launch();
// Pinned, and deliberately not UTC: the two instants share one zone selector now, and a zone with a
// non-zero offset is the only setting where "the picker shows the instant that got written" is a
// statement with any content in it.
const page = await browser.newPage({
  locale: 'en-US',
  timezoneId: 'Asia/Taipei',
  viewport: { width: 1400, height: 1600 }
});

const problems = [];
page.on('pageerror', (e) => problems.push('pageerror: ' + e));
page.on('console', (m) => m.type() === 'error' && problems.push('console: ' + m.text()));

const saved = [];
page.on('download', async (d) => {
  const name = d.suggestedFilename();
  await d.saveAs(join(out, name));
  saved.push(name);
});

await page.goto(`${ORIGIN}/researcher/`, { waitUntil: 'networkidle' });

// Keys. Both are generated in the tab, and both private halves are offered as downloads.
await page.locator('[data-testid="field-signer.key_id"] input').fill('e2e-signer-2026');
await page.locator('[data-testid="field-export.researcher_key_id"] input').fill('e2e-hpke-2026');
const generate = page.getByRole('button', { name: 'Generate', exact: true });
await generate.nth(0).click();
await generate.nth(1).click();
await page.waitForTimeout(600);
const download = page.getByRole('button', { name: 'Download', exact: true });
await download.nth(0).click();
await download.nth(1).click();
await page.waitForTimeout(600);

// The study.
await page.getByRole('button', { name: 'Next', exact: true }).click();
await page.waitForSelector('[data-testid="step-study"]');
await page.waitForTimeout(400);
const fill = async (label, value) => {
  const id = await page.evaluate((needle) => {
    for (const el of document.querySelectorAll('input, textarea')) {
      const label = el.id && document.querySelector(`label[for="${el.id}"]`);
      if (label?.textContent?.trim().toLowerCase().includes(needle.toLowerCase())) return el.id;
    }
    return null;
  }, label);
  if (!id) throw new Error(`no field labelled ${label}`);
  await page.locator('#' + id).fill(value);
};
// No identifier is typed: the experiment is named from the title and the configuration from the
// document's own bytes, so the two the CLI prints back are the two this page derived.
await fill('title', TITLE);
await fill('researcher', RESEARCHER);
await fill('contact', 'e2e@example.invalid');
await fill('purpose', 'Prove the page is wired to the library.');
await fill('consent document version', 'consent-e2e-1');
await fill('consent summary', CONSENT);

// One selector governs both instants. Switched to UTC, each picker must read as the instant that
// will be written, character for character — which is what gets asserted against the file below.
const zone = page.getByRole('combobox', { name: 'Time zone' });
if ((await zone.count()) !== 1) throw new Error('the study step has no shared zone selector');
await zone.selectOption('UTC');
await page.waitForTimeout(200);
const wall = await page
  .locator('[data-testid="step-study"] input[type="datetime-local"]')
  .evaluateAll((inputs) =>
    // A `datetime-local` drops `:00` seconds from its value; the instant never does.
    inputs.map((input) => (input.value.length === 16 ? `${input.value}:00` : input.value))
  );
if (wall.length !== 2) throw new Error(`expected two instants, found ${wall.length}`);

// How long one participant runs, from the preset row rather than a typed number.
await page.locator(`[data-testid="preset-duration_hours-${DURATION_HOURS}"]`).click();

await page.getByRole('switch', { name: /App activity/ }).first().click();
await page.getByRole('switch', { name: /^Motion/ }).first().click();
await page.waitForTimeout(300);

// Sign. The identifiers are read off the page before the click, because the claim under test is
// that the file is named what the researcher was shown it would be named.
await page.getByRole('button', { name: 'Next', exact: true }).click();
await page.waitForSelector('[data-testid="identity-readout"]');
await page.waitForTimeout(400);
// `:first-child`, because `CopyButton` leaves its own live region as a sibling of the value.
const [experimentId, configurationId] = await page
  .locator('[data-testid="identity-readout"] dd > span:first-child')
  .evaluateAll((spans) => spans.map((span) => span.textContent.trim()));

await page
  .locator('[data-testid="step-sign"]')
  .getByRole('button', { name: 'Sign', exact: true })
  .click();
await page.waitForSelector('[data-testid="step-files"]');
await page.waitForTimeout(800);

// The fingerprint as the page draws it: eight groups, which is what a researcher publishes.
const fingerprint = (
  await page.locator('[data-testid="fingerprint"] .fingerprint__group').allTextContents()
).join(' ');

// Both artifacts. The canonical JSON is not a nicety — `decrypt --config` needs it, and no command
// extracts one from the `.adccfg`.
await page.getByRole('button', { name: /study-canonical\.json/ }).first().click();
await page.getByRole('button', { name: /study\.adccfg/ }).first().click();
await page.waitForTimeout(600);
await browser.close();

const fail = (message) => {
  console.error('FAIL ' + message);
  process.exit(1);
};

if (problems.length) fail('the page logged errors:\n  ' + problems.join('\n  '));
for (const name of [
  'study-signing-private.key',
  'export-hpke-private.json',
  'study-canonical.json',
  'study.adccfg'
]) {
  if (!saved.includes(name)) fail(`the page never offered ${name}`);
  if (!existsSync(join(out, name))) fail(`${name} was offered but did not arrive`);
}

// ---------------------------------------------------------------------------------------------
// What the page showed.
// ---------------------------------------------------------------------------------------------

if (!ID_PATTERN.test(experimentId)) fail(`the page showed an illegal experiment id: ${experimentId}`);
if (!ID_PATTERN.test(configurationId)) {
  fail(`the page showed an illegal configuration id: ${configurationId}`);
}
if (experimentId !== EXPECTED_EXPERIMENT_ID) {
  fail(`the title should slug to ${EXPECTED_EXPERIMENT_ID}, and the page showed ${experimentId}`);
}
// The stem is the experiment; the six characters after it are the digest of the document, which
// moves with the issue time and so cannot be written down here.
if (!new RegExp(`^${EXPECTED_EXPERIMENT_ID}-[0-9a-z]{6}$`).test(configurationId)) {
  fail(`the configuration id is not a digest under the experiment: ${configurationId}`);
}
if (!/^([0-9A-F]{4} ){7}[0-9A-F]{4}$/.test(fingerprint)) {
  fail(`the page drew a fingerprint in the wrong shape: ${JSON.stringify(fingerprint)}`);
}

// ---------------------------------------------------------------------------------------------
// What the file says.
// ---------------------------------------------------------------------------------------------

const canonical = readFileSync(join(out, 'study-canonical.json'));
const text = canonical.toString('utf8');
const document = JSON.parse(text);

const claim = (ok, message) => ok || fail(message);
claim(document.experiment_id === experimentId, 'the file is not named what the page showed');
claim(document.configuration_id === configurationId, 'the configuration id in the file differs');
claim(document.title === TITLE, 'the title did not survive the round trip');
claim(document.researcher.name === RESEARCHER, 'the researcher name did not survive');
claim(document.consent.summary === CONSENT, 'the consent summary did not survive');
claim(document.duration_hours === DURATION_HOURS, `duration_hours is ${document.duration_hours}`);
claim(document.issued_at === `${wall[0]}Z`, `issued_at is ${document.issued_at}, picker ${wall[0]}`);
claim(
  document.expires_at === `${wall[1]}Z`,
  `expires_at is ${document.expires_at}, picker ${wall[1]}`
);
claim(document.collectors.length === 2, `${document.collectors.length} collectors, expected 2`);

// Gson's escape table and nothing else: these appear as themselves, and these are escaped.
for (const raw of ['—', '瀏覽器建立 🔬', '正體中文 & <ok> = fine.']) {
  claim(text.includes(raw), `the encoder escaped ${JSON.stringify(raw)}, which Gson emits raw`);
}
for (const escaped of ['E2E Lab \\"Verification\\" \\\\ Group', 'app activity.\\nStays on the phone']) {
  claim(text.includes(escaped), `the encoder did not write ${JSON.stringify(escaped)}`);
}

// The envelope carries exactly the canonical bytes the page also handed over as a file. A
// researcher who archives one and distributes the other is archiving the right thing.
// `ADCCFG01`, uint16 key-id length, int32 configuration length, uint16 signature length.
const envelope = readFileSync(join(out, 'study.adccfg'));
claim(envelope.subarray(0, 8).toString('latin1') === 'ADCCFG01', 'the envelope has no magic');
const keyIdLength = envelope.readUInt16BE(8);
const configurationLength = envelope.readInt32BE(10);
const signatureLength = envelope.readUInt16BE(14);
claim(
  envelope.length === 16 + keyIdLength + configurationLength + signatureLength,
  'the envelope lengths do not add up to its size'
);
claim(
  envelope.subarray(16, 16 + keyIdLength).toString('utf8') === 'e2e-signer-2026',
  'the envelope names a different signer'
);
claim(
  envelope
    .subarray(16 + keyIdLength, 16 + keyIdLength + configurationLength)
    .equals(canonical),
  'the .adccfg does not carry the canonical JSON the page offered beside it'
);

// ---------------------------------------------------------------------------------------------
// What the app's own code makes of it.
// ---------------------------------------------------------------------------------------------

if (!existsSync(CLI)) fail(`build the CLI first: ./gradlew :researcher-tools:installDist`);

// The arbiter of the encoding. Anything this page escaped differently comes back different.
const roundtrip = join(out, 'roundtrip.json');
execFileSync(CLI, ['canonicalize', '--input', join(out, 'study-canonical.json'), '--output', roundtrip]);
if (!readFileSync(roundtrip).equals(canonical)) {
  fail('the Kotlin codec re-canonicalises this file to different bytes');
}

const verdict = execFileSync(CLI, ['check-config', '--envelope', join(out, 'study.adccfg')], {
  encoding: 'utf8'
});
const [validLine, signerLine] = verdict.trim().split('\n');
if (validLine !== `valid ${experimentId} ${configurationId}`) {
  fail(`the CLI refused the file the page produced, or renamed it:\n${verdict}`);
}
if (signerLine !== `signer e2e-signer-2026 ${fingerprint}`) {
  fail(`the fingerprint the page published is not the one the verifier computes:\n${signerLine}`);
}

console.log(verdict.trim());
console.log(`\nPASS — ${saved.length} files, and the app's own verifier accepts the study.`);
