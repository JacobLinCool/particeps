/**
 * Focused browser checks for the researcher's local QR handoff.
 * Run against Vite dev: npm run dev -- --host 127.0.0.1 --port 4187
 * Then: ORIGIN=http://127.0.0.1:4187 node e2e/join-qr.mjs
 */
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { chromium } from 'playwright';

const origin = process.env.ORIGIN ?? 'http://127.0.0.1:4187';
const browser = await chromium.launch();
const envelope = [1, 2, 3];
const fingerprint = '0123456789ABCDEFFEDCBA9876543210';

try {
  for (const locale of ['en', 'zh-TW']) {
    const page = await browser.newPage({ locale });
    const externalRequests = [];
    page.on('request', (request) => {
      if (!request.url().startsWith(origin) && !request.url().startsWith('data:')) {
        externalRequests.push(request.url());
      }
    });
    await page.goto(origin, { waitUntil: 'networkidle' });
    await page.evaluate(async ({ locale, envelope, fingerprint }) => {
      const [{ mount }, { default: JoinLinkPanel }, { en }, { zhTW }] = await Promise.all([
        import('/node_modules/svelte/src/index-client.js'),
        import('/src/routes/researcher/JoinLinkPanel.svelte'),
        import('/src/lib/i18n/en.ts'),
        import('/src/lib/i18n/zh-TW.ts')
      ]);
      const target = document.createElement('div');
      target.id = 'qr-handoff-test';
      document.body.append(target);
      mount(JoinLinkPanel, {
        target,
        props: {
          envelope: new Uint8Array(envelope), fingerprint, assignedParticipantId: null,
          m: locale === 'en' ? en : zhTW
        }
      });
    }, { locale, envelope, fingerprint });

    const panel = page.locator('#qr-handoff-test');
    const input = panel.getByRole('textbox');
    const download = panel.getByRole('button', {
      name: locale === 'en' ? 'Download QR code' : '下載 QR Code', exact: true
    });
    assert.equal(await download.count(), 0);
    const artifactUrl = 'https://example.org/studies/study.partcfg';
    await input.fill(artifactUrl);
    await download.waitFor();
    const link = await panel.locator('code').textContent();
    const parsed = new URL(link);
    assert.equal(parsed.searchParams.get('artifact'), artifactUrl);
    assert.equal(parsed.searchParams.get('sha256'), createHash('sha256').update(Buffer.from(envelope)).digest('hex'));
    assert.equal(parsed.searchParams.get('signer_fingerprint'), fingerprint);

    const preview = await panel.getByRole('img').getAttribute('src');
    const saved = page.waitForEvent('download');
    await download.click();
    const artifact = await saved;
    assert.equal(artifact.suggestedFilename(), 'particeps-study-qr.svg');
    const chunks = [];
    for await (const chunk of await artifact.createReadStream()) chunks.push(chunk);
    assert.equal(Buffer.concat(chunks).toString('utf8'), decodeURIComponent(preview.slice(preview.indexOf(',') + 1)));

    // An invalid address must remove the previous image and its download action.
    await input.fill('http://example.org/study.partcfg');
    await panel.getByRole('img').waitFor({ state: 'detached' });
    assert.equal(await download.count(), 0);

    // This is a valid join URI but exceeds QR capacity. Copying the link remains possible.
    await input.fill(`https://example.org${'/a'.repeat(900)}`);
    await panel.getByText(locale === 'en'
      ? 'This link could not be made into a QR code. Try a shorter HTTPS address, or share the join link.'
      : '此連結無法產生 QR Code。請改用較短的 HTTPS 位址，或分享加入連結。', { exact: true }).waitFor();
    assert.equal(await download.count(), 0);
    assert.equal(await panel.getByRole('img').count(), 0);
    assert.equal(await panel.locator('[data-testid="copy-join-link"]').count(), 1);

    await input.fill('https://example.org/studies/replacement.partcfg');
    await download.waitFor();
    assert.match(await panel.locator('code').textContent(), /replacement\.partcfg/);
    assert.deepEqual(externalRequests, []);
    await page.close();
    console.log(`PASS ${locale}: exact SVG download, immutable pointer, stale QR removal, capacity failure, recovery, local generation`);
  }
} finally {
  await browser.close();
}
