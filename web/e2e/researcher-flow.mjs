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
 *   1. What the page *showed* is what the file *says*, and both key names are functions of the keys
 *      they name. All four identifiers are derived now rather than typed — the study's two names,
 *      and the two key names — so the sign step's readout is a claim the page makes, and the CLI
 *      printing the same names back is the only thing that tests it. Same for the fingerprint on
 *      the files step: it is what goes into a recruitment sheet, and `check-config` recomputes it
 *      from the file. Nothing in this script types an identifier, which is what makes the
 *      comparison worth making — and because agreement alone would still hold for a page that
 *      invented one string and printed it everywhere, both key names are also recomputed here from
 *      the key material in the signed file, by an implementation that is not the site's.
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
import { createHash, createPrivateKey, createPublicKey } from 'node:crypto';
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

const fail = (message) => {
  console.error('FAIL ' + message);
  process.exit(1);
};

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

// Keys, which is the second step now: the page opens on the study, so the rail has to be used to
// get here. Nothing is typed and no button is pressed to make the keys — the step generates both
// pairs on arrival, and both names derive from the key material. The two tiles are taken by the
// names they carry, which are those derived names with `-private.key` / `-private.json` after them.
await page.locator('[data-testid="rail-keys"]').click();
await page.waitForSelector('[data-testid="step-keys"]');
await page.waitForTimeout(600);
const signingTile = page.getByRole('button', { name: /signer-[0-9a-z]{13}-private\.key$/ });
const hpkeTile = page.getByRole('button', { name: /export-[0-9a-z]{13}-private\.json$/ });
if ((await signingTile.count()) !== 1) fail('the keys step did not make a signing key on arrival');
if ((await hpkeTile.count()) !== 1) fail('the keys step did not make an export key on arrival');
// The step offers nothing to type at all. A key-ID field here is the thing that was removed, and a
// script that simply stops filling one would keep passing if it came back.
const typeable = await page.locator('[data-testid="step-keys"]').getByRole('textbox').count();
if (typeable !== 0) fail(`the keys step offers ${typeable} fields to type into, and should offer 0`);
await signingTile.click();
await hpkeTile.click();
await page.waitForTimeout(600);

// The study. Reached by name off the rail rather than by pressing Next, so the script says which
// step it wants and does not quietly encode where that step sits in the order.
await page.locator('[data-testid="rail-study"]').click();
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

// Whether a participant may decline a source. It is pressed on one of the two cards and left alone
// on the other, so the file below has to disagree with itself in the right place: a control wired
// to the wrong collector, or to nothing, writes the same value into both entries.
//
// Taken by role and name inside the card it belongs to, not by a class or an array index. The row
// is a group named for the collector holding a switch and this button, and that is the whole of
// what this script is entitled to know about how the card is built.
const motionCard = page.locator('[data-testid="collector-accelerometer.v1"]');
const motionRequired = motionCard.getByRole('button', { name: 'Required' });
if ((await motionRequired.count()) !== 1) {
  fail('the Motion card offers no Required control while the collector is on');
}
await motionRequired.click();
await page.waitForTimeout(200);
if ((await motionRequired.getAttribute('aria-pressed')) !== 'true') {
  fail('pressing Required left the control unpressed');
}

// Sign. The identifiers are read off the page before the click, because the claim under test is
// that the file is named what the researcher was shown it would be named.
await page.locator('[data-testid="rail-sign"]').click();
await page.waitForSelector('[data-testid="identity-readout"]');
await page.waitForTimeout(400);
// `:first-child`, because `CopyButton` leaves its own live region as a sibling of the value. Four
// rows now: the two names of the document, and the two names of the keys — every one of them
// derived, and every one of them a claim this page makes that the CLI has to agree with.
const [experimentId, configurationId, signerKeyId, exportKeyId] = await page
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

// ---------------------------------------------------------------------------------------------
// The read step's offer of what this tab already holds.
//
// `researcher-tools decrypt` takes three files, and two of them are files this tab has just made.
// The step offers them rather than asking a researcher to go and find what they downloaded a
// minute ago — but only once a signature exists, because an export key's public half reaches a
// phone only inside a signed configuration, and a key with nothing signed can have sealed nothing.
// So this is the assertion that the offer appears exactly when it is true, and fills the two
// inputs with the two files whose names are on the disk beside this script.
//
// The decryption itself is proved elsewhere and against the other implementation: `tests/
// compat.spec.ts` has the JVM open a bundle this codebase sealed, and open the site's reader on it.
// ---------------------------------------------------------------------------------------------
await page.locator('[data-testid="rail-read"]').click();
await page.waitForSelector('[data-testid="step-read"]');
for (const id of ['read-configuration-session', 'read-key-session']) {
  if ((await page.locator(`[data-testid="${id}"]`).count()) !== 1) {
    fail(`the read step did not offer ${id} after signing`);
  }
  await page.locator(`[data-testid="${id}"]`).click();
}
const staged = await page.locator('[data-testid="step-read"] .note').allTextContents();
for (const name of ['study-canonical.json', 'export-hpke-private.json']) {
  if (!staged.some((line) => line.includes(name))) {
    fail(`the read step did not name ${name} after taking it from this tab`);
  }
}
// Two of three. Nothing decrypts until a bundle arrives, and the control says so by being dead.
if (await page.locator('[data-testid="read-open"]').isEnabled()) {
  fail('the read step offered to open a bundle it has not been given');
}

await browser.close();

if (problems.length) fail('the page logged errors:\n  ' + problems.join('\n  '));
// A private key file is named after the key inside it, so the file on disk *is* the string
// `researcher-tools sign --key-id` wants.
for (const name of [
  `${signerKeyId}-private.key`,
  `${exportKeyId}-private.json`,
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
// Both key names are 64 bits of a domain-separated digest of the public key, as thirteen base-36
// characters behind a word stem. Nothing here was typed, so the shape is the whole claim.
for (const [what, id, shape] of [
  ['signer', signerKeyId, /^signer-[0-9a-z]{13}$/],
  ['export', exportKeyId, /^export-[0-9a-z]{13}$/]
]) {
  if (!shape.test(id)) fail(`the page showed a ${what} key id in the wrong shape: ${id}`);
  if (!ID_PATTERN.test(id)) fail(`the page showed an illegal ${what} key id: ${id}`);
}
if (!/^([0-9A-F]{4} ){7}[0-9A-F]{4}$/.test(fingerprint)) {
  fail(`the page drew a fingerprint in the wrong shape: ${JSON.stringify(fingerprint)}`);
}
// The key ID must not be a truncation or a re-encoding of the fingerprint: publishing a second
// string that shares the fingerprint's leading characters teaches prefix comparison, which is what
// the fingerprint has to resist.
if (fingerprint.replace(/ /g, '').toLowerCase().includes(signerKeyId.slice(7))) {
  fail('the signer key id is a substring of the fingerprint, which it must never be');
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
claim(document.signer.key_id === signerKeyId, 'the file names a signer the page did not show');
claim(
  document.export.researcher_key_id === exportKeyId,
  'the file names an export key the page did not show'
);
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

// The Required control, from the other end. Pressed on Motion and untouched on App activity, so a
// control that writes nowhere and a control that writes into every collector both fail here.
const requiredOf = Object.fromEntries(document.collectors.map((c) => [c.id, c.required]));
claim(
  requiredOf['accelerometer.v1'] === true,
  `Required was pressed on Motion and the file says ${JSON.stringify(requiredOf['accelerometer.v1'])}`
);
claim(
  requiredOf['app_lifecycle.v1'] === false,
  'Required was pressed on Motion alone and App activity came out required too'
);

// Gson's escape table and nothing else: these appear as themselves, and these are escaped.
for (const raw of ['—', '瀏覽器建立 🔬', '正體中文 & <ok> = fine.']) {
  claim(text.includes(raw), `the encoder escaped ${JSON.stringify(raw)}, which Gson emits raw`);
}
for (const escaped of ['E2E Lab \\"Verification\\" \\\\ Group', 'app activity.\\nStays on the phone']) {
  claim(text.includes(escaped), `the encoder did not write ${JSON.stringify(escaped)}`);
}

// ---------------------------------------------------------------------------------------------
// What the two key names are made of.
//
// Everything above would still pass on a page that invented one string per key and printed the
// same invention in the readout, in the filename and in the file. The property the Keys step now
// rests on is stronger than agreement: each name is a function of the key it names, so a reader
// holding only the `.adccfg` can recompute it, and the same key gets the same name in the second
// arm of a study whether it was generated here, imported, or read back out of a configuration.
//
// `lib/adc/ids.ts` is re-implemented below from its own specification rather than imported — a
// derivation checked against itself proves nothing. Sixty-four bits of SHA-256 over a
// domain-separated *raw* public key (not the DER, not the Tink JSON), as thirteen base-36
// characters behind a word stem.
// ---------------------------------------------------------------------------------------------

const keyTag = (domain, raw) =>
  BigInt(
    '0x' +
      createHash('sha256')
        .update(Buffer.concat([Buffer.from(domain, 'ascii'), Buffer.from(raw)]))
        .digest()
        .subarray(0, 8)
        .toString('hex')
  )
    .toString(36)
    .padStart(13, '0');

/** The one length-delimited field `number` at the top level of a protobuf message, or `null`. */
function field(message, number) {
  let at = 0;
  const varint = () => {
    let value = 0;
    let shift = 0;
    for (;;) {
      const byte = message[at];
      at += 1;
      value += (byte & 0x7f) * 2 ** shift;
      if ((byte & 0x80) === 0) return value;
      shift += 7;
    }
  };
  while (at < message.length) {
    const key = varint();
    const wire = key & 7;
    if (wire === 2) {
      const length = varint();
      if (key >>> 3 === number) return message.subarray(at, at + length);
      at += length;
    } else if (wire === 0) varint();
    else if (wire === 5) at += 4;
    else if (wire === 1) at += 8;
    else throw new Error(`unreadable protobuf wire type ${wire}`);
  }
  return null;
}

/** X.509 SubjectPublicKeyInfo for Ed25519: fixed length, so the last 32 bytes are the key. */
const X509_PREFIX = Buffer.from('302a300506032b6570032100', 'hex');

const spki = Buffer.from(document.signer.public_key, 'base64');
claim(
  spki.length === 44 && spki.subarray(0, 12).equals(X509_PREFIX),
  'signer.public_key is not an X.509 Ed25519 key'
);
const signerRaw = spki.subarray(12);

const exportRaw = field(
  Buffer.from(document.export.tink_hpke_public_keyset.key[0].keyData.value, 'base64'),
  3
);
claim(exportRaw?.length === 32, 'the public keyset in the file carries no 32-byte X25519 point');

claim(
  document.signer.key_id === `signer-${keyTag('adc:signer-key-id:v1:', signerRaw)}`,
  `signer.key_id is not the digest of the key it names: ${document.signer.key_id}`
);
claim(
  document.export.researcher_key_id === `export-${keyTag('adc:export-key-id:v1:', exportRaw)}`,
  `export.researcher_key_id is not the digest of the key it names: ${document.export.researcher_key_id}`
);
// Two different keys, so the two names must differ even where the stems are stripped off. A
// derivation missing its domain separation would put one digest behind both words.
claim(
  document.signer.key_id.slice(7) !== document.export.researcher_key_id.slice(7),
  'both key names carry the same digest, so the two roles are not separated'
);

// And the two files the researcher keeps hold those same keys, so a file named after a key is
// named after the key that is actually inside it.
const signingPrivate = readFileSync(join(out, `${signerKeyId}-private.key`), 'utf8');
const derivedSpki = createPublicKey(
  createPrivateKey({
    key: Buffer.from(signingPrivate.trim(), 'base64'),
    format: 'der',
    type: 'pkcs8'
  })
).export({ format: 'der', type: 'spki' });
claim(
  Buffer.from(derivedSpki).equals(spki),
  `${signerKeyId}-private.key is not the key the configuration is signed under`
);

const privateKeyset = JSON.parse(readFileSync(join(out, `${exportKeyId}-private.json`), 'utf8'));
const privateValue = Buffer.from(privateKeyset.key[0].keyData.value, 'base64');
const publicInPrivate = field(privateValue, 2);
claim(publicInPrivate !== null, `${exportKeyId}-private.json carries no public half`);
claim(
  Buffer.from(field(publicInPrivate, 3) ?? []).equals(exportRaw),
  `${exportKeyId}-private.json cannot decrypt what this study encrypts to`
);

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
  envelope.subarray(16, 16 + keyIdLength).toString('utf8') === signerKeyId,
  'the envelope names a signer other than the one the page showed'
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
if (signerLine !== `signer ${signerKeyId} ${fingerprint}`) {
  fail(`the signer the CLI reads back is not the one the page showed:\n${signerLine}`);
}

console.log(verdict.trim());
console.log(`\nPASS — ${saved.length} files, and the app's own verifier accepts the study.`);
