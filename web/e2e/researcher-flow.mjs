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
 *   pnpm build && pnpm exec http-server build -p 4173   # or any static server
 *   node e2e/researcher-flow.mjs
 */
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:4173';
const CLI = join(
  import.meta.dirname,
  '../../researcher-tools/build/install/researcher-tools/bin/researcher-tools'
);

const out = mkdtempSync(join(tmpdir(), 'adc-e2e-'));
const browser = await chromium.launch();
const page = await browser.newPage({ locale: 'en-US', viewport: { width: 1400, height: 1600 } });

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
await page.locator('#s4-control').fill('e2e-signer-2026');
await page.locator('#s6-control').fill('e2e-hpke-2026');
const generate = page.getByRole('button', { name: 'Generate', exact: true });
await generate.nth(0).click();
await generate.nth(1).click();
await page.waitForTimeout(600);
const download = page.getByRole('button', { name: 'Download', exact: true });
await download.nth(0).click();
await download.nth(1).click();
await page.waitForTimeout(600);

// The study. Deliberately hostile text: an em dash, CJK, an emoji, a quote, a backslash, a newline,
// and characters Gson leaves unescaped, so an encoder that "helpfully" escapes them is caught here.
await page.getByRole('button', { name: 'Next', exact: true }).click();
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
await fill('experiment id', 'e2e-web-study');
await fill('configuration id', 'e2e-web-config');
await fill('title', 'Browser-authored study — 瀏覽器建立 🔬');
await fill('researcher', 'E2E Lab "Verification" \\ Group');
await fill('contact', 'e2e@example.invalid');
await fill('purpose', 'Prove the page is wired to the library.');
await fill('consent document version', 'consent-e2e-1');
await fill(
  'consent summary',
  'Collects motion and app activity.\nStays on the phone until exported. 正體中文 & <ok> = fine.'
);
await page.getByRole('switch', { name: /App activity/ }).first().click();
await page.getByRole('switch', { name: /^Motion/ }).first().click();
await page.waitForTimeout(300);

// Sign, and take the file.
await page.getByRole('button', { name: 'Next', exact: true }).click();
await page.waitForTimeout(400);
await page.getByRole('button', { name: /^Sign/ }).last().click();
await page.waitForTimeout(1200);
await page.getByRole('button', { name: /study\.adccfg/ }).first().click();
await page.waitForTimeout(600);
await browser.close();

const fail = (message) => {
  console.error('FAIL ' + message);
  process.exit(1);
};

if (problems.length) fail('the page logged errors:\n  ' + problems.join('\n  '));
for (const name of ['study-signing-private.key', 'export-hpke-private.json', 'study.adccfg']) {
  if (!saved.includes(name)) fail(`the page never offered ${name}`);
  if (!existsSync(join(out, name))) fail(`${name} was offered but did not arrive`);
}

if (!existsSync(CLI)) fail(`build the CLI first: ./gradlew :researcher-tools:installDist`);
const verdict = execFileSync(CLI, ['check-config', '--envelope', join(out, 'study.adccfg')], {
  encoding: 'utf8'
});
if (!verdict.startsWith('valid e2e-web-study e2e-web-config')) {
  fail(`the CLI refused the file the page produced:\n${verdict}`);
}

console.log(verdict.trim());
console.log(`\nPASS — ${saved.length} files, and the app's own verifier accepts the study.`);
