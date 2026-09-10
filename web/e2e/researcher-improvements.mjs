/** Public-fixture regression against an existing server. No server is started here. */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, writeFileSync, rmSync } from 'node:fs';
import { resolve, join } from 'node:path';
const root = resolve(import.meta.dirname, '../..');
const output = join(root, 'output/playwright/researcher-improvements');
mkdirSync(output, { recursive: true });
const origin = process.env.ORIGIN ?? 'http://127.0.0.1:4173';
const cli = join(root, 'researcher-tools/build/install/researcher-tools/bin/researcher-tools');
const fixturePath = join(root, 'researcher-tools/examples/five-day-speed-study.json');
const fixture = JSON.parse(readFileSync(fixturePath, 'utf8'));
const canonicalPath = join(output, 'five-day.canonical.json');
rmSync(canonicalPath, { force: true });
execFileSync(cli, ['canonicalize', '--input', fixturePath, '--output', canonicalPath]);
const browser = await chromium.launch();
const page = await browser.newPage({ locale: 'en-US', timezoneId: 'Asia/Taipei', viewport: { width: 1440, height: 1100 } });
const errors = [];
page.on('pageerror', error => errors.push(String(error)));
page.on('console', message => { if (message.type() === 'error') errors.push(message.text()); });
const file = async (id, payload) => {
  const chooser = page.waitForEvent('filechooser');
  await page.getByTestId(id).click();
  await (await chooser).setFiles(payload);
};
const navigate = async step => { await page.getByTestId(`rail-${step}`).click(); await page.getByTestId(`step-${step}`).waitFor(); };
const save = async name => {
  const next = page.waitForEvent('download');
  await page.getByTestId('save-draft').click();
  const path = join(output, name);
  await (await next).saveAs(path);
  return { path, value: JSON.parse(readFileSync(path, 'utf8')) };
};
const ruleLayout = async label => {
  const geometry = await page.getByTestId('study-overview').evaluate(overview => {
    const rules = overview.querySelector('[data-testid="overview-rules"]');
    const rect = element => element.getBoundingClientRect().toJSON();
    return {
      rules: rect(rules),
      assumptions: rect(overview.querySelector('[data-testid="overview-assumptions"]')),
      rows: [...rules.querySelectorAll(':scope > details')].map(row => ({
        id: row.querySelector('code').textContent,
        bounds: rect(row),
        children: [...row.children].filter(child => row.open || child.tagName === 'SUMMARY').map(rect),
        titleLines: [...row.querySelector('code').getClientRects()].map(line => line.toJSON())
      })),
      documentWidth: document.documentElement.scrollWidth,
      viewportWidth: innerWidth
    };
  });
  assert.equal(geometry.rows.length, fixture.automations.length, `${label}: every rule is rendered`);
  for (const [index, row] of geometry.rows.entries()) {
    for (const child of [...row.children, ...row.titleLines]) {
      assert(child.top >= row.bounds.top - 1 && child.bottom <= row.bounds.bottom + 1,
        `${label}: ${row.id} contains its visible content vertically`);
      assert(child.left >= row.bounds.left - 1 && child.right <= row.bounds.right + 1,
        `${label}: ${row.id} contains its visible content horizontally`);
    }
    const nextTop = geometry.rows[index + 1]?.bounds.top ?? geometry.assumptions.top;
    assert(row.bounds.bottom <= nextTop + 1, `${label}: ${row.id} does not overlap the following row or section`);
    assert(row.bounds.bottom <= geometry.rules.bottom + 1, `${label}: all rows remain inside the rules section`);
  }
  assert(geometry.rules.bottom <= geometry.assumptions.top, `${label}: assumptions follow all rules`);
  assert(geometry.documentWidth <= geometry.viewportWidth, `${label}: no document overflow`);
  return geometry;
};
try {
  await page.goto(`${origin}/researcher/`, { waitUntil: 'networkidle' });
  const initial = await save('initial.partdraft');
  await file('load-configuration', canonicalPath);
  await page.waitForFunction(title => document.querySelector('[data-testid="field-title"] input')?.value === title, fixture.title);
  const imported = await save('imported.partdraft');
  assert.equal(imported.value.configuration.signer.public_key, fixture.signer.public_key);
  assert.equal(imported.value.configuration.export.hpke_public_key, fixture.export.hpke_public_key);
  assert.notEqual(initial.value.configuration.signer.public_key, imported.value.configuration.signer.public_key);
  assert(!JSON.stringify(imported.value).includes('private_key'), 'portable drafts exclude private keys');
  await navigate('sign');
  assert(await page.getByTestId('sign').isDisabled(), 'imported signing key and review are required');
  assert.match(await page.getByTestId('step-sign').innerText(), /private key|review/i);
  await navigate('overview');
  await page.getByTestId('overview-day').selectOption('3');
  const cap = page.getByTestId('overview-lane-traffic-shaping.v1').getByRole('button', { name: /12:00–17:00.*limited-500/ });
  assert(await cap.isVisible());
  assert.match(await cap.getAttribute('class'), /band--capped/, 'an actual cap receives a distinct fill');
  const event = page.getByTestId('overview-chart').getByRole('button', { name: /^17:00,.*6 questions/ });
  assert(await event.isVisible());
  const participant = page.getByTestId('participant-preview');
  for (const question of fixture.surveys[0].questions) assert(await participant.getByText(question.prompt.default, { exact: true }).isVisible());
  assert(!(await participant.innerText()).includes('limited-500'), 'participant content excludes treatment IDs');
  await event.click();
  await page.getByTestId('overview-selection').getByRole('button', { name: 'Edit setting', exact: true }).click();
  assert(await page.locator('details[data-issue-host="automations.0"]').evaluate(element => element.open));
  assert.equal(await page.locator('details.question').count(), 6);
  const availability = page.locator('[data-testid="field-automations.0.availability_seconds"] input');
  await availability.fill('1.5');
  const unfinished = await save('unfinished.partdraft');
  assert.equal(unfinished.value.configuration.automations[0].availability_seconds, 1.5);
  await file('load-configuration', unfinished.path);
  await page.getByRole('navigation', { name: 'Study sections' }).getByRole('button', { name: 'Surveys & activities', exact: true }).click();
  await page.locator('details[data-issue-host="automations.0"] > summary').click();
  assert.equal(await availability.inputValue(), '1.5', 'unfinished numeric state survives saving and loading');
  await availability.fill('21600');
  await navigate('keys');
  await file('key-import-signing', join(root, 'researcher-tools/examples/INSECURE-demo-signing-private.key'));
  assert(await page.getByTestId('key-download-signing').isEnabled());
  await navigate('sign');
  assert(await page.getByTestId('sign').isDisabled(), 'matching key alone does not bypass review');
  await navigate('overview');
  await page.getByTestId('toggle-review.blinding').click();
  await page.getByTestId('overview-day').selectOption('3');
  const simulator = page.locator('.simulator');
  await simulator.getByRole('button', { name: 'Run scenario', exact: true }).click();
  await simulator.locator('.result').waitFor();
  await simulator.getByRole('spinbutton', { name: 'Scenario end: calendar seconds', exact: true }).fill('600');
  assert.equal(await simulator.locator('.result').count(), 0, 'editing trace hides stale simulator output');
  assert(await simulator.getByText('The study or scenario changed. Run it again to see current results.', { exact: true }).isVisible());
  await simulator.getByRole('button', { name: 'Run scenario', exact: true }).click();
  await simulator.locator('.result').waitFor();
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: join(output, 'desktop.png'), fullPage: true, animations: 'disabled' });
  await page.getByTestId('study-overview').screenshot({ path: join(output, 'overview.png'), animations: 'disabled' });
  await page.setViewportSize({ width: 390, height: 844 });
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }));
  await page.screenshot({ path: join(output, 'mobile.png'), fullPage: true, animations: 'disabled' });
  const mobileGeometry = await page.evaluate(() => ({ width: innerWidth, scrollWidth: document.documentElement.scrollWidth,
    overflow: [...document.querySelectorAll('body *')].map(element => ({ tag: element.tagName, class: element.className, testid: element.getAttribute('data-testid'), right: element.getBoundingClientRect().right })).filter(item => item.right > innerWidth + 1).slice(0, 20) }));
  assert(mobileGeometry.scrollWidth <= mobileGeometry.width, `mobile document has no horizontal overflow: ${JSON.stringify(mobileGeometry)}`);
  await page.setViewportSize({ width: 1440, height: 1100 });
  await navigate('sign');
  assert(await page.getByTestId('sign').isEnabled(), 'signing requires no export private key');
  await page.getByTestId('sign').click();
  await page.getByTestId('step-files').waitFor();
  const corpus = JSON.parse(readFileSync(join(root, 'protocol/v1/conformance-vectors.json'), 'utf8'));
  const publicBundle = corpus.valid.bundle;
  await navigate('read');
  await file('read-configuration', { name: 'conformance.json', mimeType: 'application/json', buffer: Buffer.from(corpus.valid.signed_configuration.canonical_jcs_utf8_hex, 'hex') });
  await file('read-bundle', { name: 'conformance.partexp', mimeType: 'application/octet-stream', buffer: Buffer.from(publicBundle.container_hex, 'hex') });
  await file('read-key', { name: 'INSECURE-conformance-private.key', mimeType: 'text/plain', buffer: Buffer.from(publicBundle.researcher_private_key_base64url) });
  await page.getByTestId('read-open').click();
  await page.getByTestId('read-summary').waitFor();
  const figures = await page.getByTestId('read-figures').innerText();
  assert.match(figures, /1\s*\/\s*1/, 'same collector population is used for file and lifetime');
  assert.match(figures, /1–3\s*\/\s*3/, 'commit coverage is compared to durable head');
  assert.match(await page.getByTestId('read-summary').innerText(), /commit 1 through the declared durable head/);

  // Exercise real imported rule data: the longest valid identifier must wrap on narrow screens.
  const layoutDraft = structuredClone(imported.value);
  layoutDraft.configuration.title = 'Rule layout regression';
  layoutDraft.configuration.automations[0].id = 'a'.repeat(64);
  await file('load-configuration', { name: 'rule-layout.partdraft', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify(layoutDraft)) });
  await page.getByRole('dialog').filter({ hasText: 'Replace the current study with this file?' }).getByRole('button', { name: 'Confirm', exact: true }).click();
  await page.waitForFunction(() => document.querySelector('[data-testid="field-title"] input')?.value === 'Rule layout regression');
  await navigate('overview');
  const rules = page.getByTestId('overview-rules');
  const rows = rules.locator(':scope > details');
  await rules.locator(':scope > summary').click();
  await page.getByTestId('overview-assumptions').locator(':scope > summary').click();
  const ruleScreenshots = [];
  const ruleGeometries = {};
  for (const locale of ['en', 'zh-TW']) {
    await page.getByTestId('locale-menu').filter({ visible: true }).click();
    await page.getByRole('radio', { name: locale === 'en' ? 'English' : '正體中文', exact: true }).click();
    for (const [size, viewport] of [['desktop', { width: 1440, height: 1100 }], ['mobile', { width: 390, height: 844 }]]) {
      await page.setViewportSize(viewport);
      const colorScheme = locale === 'en' ? 'light' : 'dark';
      await page.emulateMedia({ colorScheme });
      const label = `${locale}-${size}-${colorScheme}`;
      const closed = await ruleLayout(`${label} closed`);
      if (size === 'mobile') assert(closed.rows[0].titleLines.length > 1, `${label}: long rule identifier wraps`);
      await rows.first().locator(':scope > summary').press('Enter');
      await rows.last().locator(':scope > summary').press('Space');
      assert(await rows.first().evaluate(row => row.open), `${label}: Enter expands the first rule`);
      assert(await rows.last().evaluate(row => row.open), `${label}: Space expands the last rule`);
      ruleGeometries[label] = { closed, expanded: await ruleLayout(`${label} expanded`) };
      const screenshot = `rules-${label}.png`;
      // Keep the sticky toolbar out of this crop, which can be taller than the viewport.
      await rules.screenshot({ path: join(output, screenshot), animations: 'disabled', style: '.site-header { visibility: hidden; }' });
      ruleScreenshots.push(screenshot);
      await rows.first().locator(':scope > summary').press('Enter');
      await rows.last().locator(':scope > summary').press('Space');
      assert(await rows.evaluateAll(elements => elements.every(row => !row.open)), `${label}: keyboard collapses the rules`);
    }
  }
  writeFileSync(join(output, 'rules-layout.json'), JSON.stringify(ruleGeometries, null, 2));
  assert.deepEqual(errors, []);
  writeFileSync(join(output, 'result.json'), JSON.stringify({ status: 'pass', checks: [
    'import preserves public keys', 'all six questions represented', 'day 3 cap and survey timeline',
    'timeline edit opens occurrence', 'fractional unfinished draft round trip', 'matching signing key',
    'review gates signing', 'export private key optional when signing', 'mobile overflow absent',
    'authenticated bundle read and honest commit coverage', 'trace edits clear stale simulator output',
    'closed and expanded rules never overlap adjacent content', 'keyboard rule disclosure',
    'long rule IDs wrap in English and Traditional Chinese across desktop/mobile and light/dark'
  ], screenshots: ['desktop.png', 'mobile.png', 'overview.png', ...ruleScreenshots] }, null, 2));
  console.log(`PASS researcher improvements: imported keys, six questions, timeline, draft round trip, review, mobile and authenticated bundle.\nArtifacts: ${output}`);
} finally { await browser.close(); }
