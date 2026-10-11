import { describe, expect, it } from 'vitest';
import { readFileSync, readdirSync } from 'node:fs';
import {
  ANDROID_APK_URL, ANDROID_RELEASE_VERSION, GLANCE,
  STUDY_CONFIGURATION_PATH, STUDY_GUIDE_PATH
} from '$lib/participant/content';
import { en, zhTW } from '$lib/participant/copy';
import { decodeEnvelope } from '$lib/particeps/envelope';
import { fingerprint, verify } from '$lib/particeps/crypto';

describe('participant page structure', () => {
  it('links only to the retained overview sections', () => {
    expect(GLANCE.map(({ href }) => href)).toEqual(['#collect', '#where']);
    expect('fingerprint' in en).toBe(false);
    expect('controls' in en).toBe(false);
    expect('fingerprint' in zhTW).toBe(false);
    expect('controls' in zhTW).toBe(false);
  });

  it('links the selected Android release from the hero in both locales', () => {
    expect(ANDROID_RELEASE_VERSION).toBe('v1.0.0-rc.17');
    expect(ANDROID_APK_URL).toBe(
      'https://github.com/JacobLinCool/particeps/releases/download/v1.0.0-rc.17/particeps-v1.0.0-rc.17.apk'
    );
    expect(en.hero.download).toBe('Download App');
    expect(zhTW.hero.download).toBe('下載 App');
  });

  it('keeps public source tiles independent of signed collector profiles', () => {
    expect('tokens' in en.sources).toBe(false);
    expect('tokens' in zhTW.sources).toBe(false);
    expect(Object.values(en.sources.detail).join(' ')).not.toMatch(
      /\{[ntd]\}|times per second|every \{|after it moves at least|selected limits/i
    );
    expect(Object.values(zhTW.sources.detail).join(' ')).not.toMatch(
      /\{[ntd]\}|每秒約記錄|每隔 \{|移動超過|實作保證的限制/
    );
  });
});

describe('invited participant study files', () => {
  it('serves the latest signed study at stable paths without researcher-only files', () => {
    expect(STUDY_CONFIGURATION_PATH).toBe('/studies/phone-usage-five-day/study.partcfg');
    expect(STUDY_GUIDE_PATH).toBe('/studies/phone-usage-five-day/participant-start.zh-TW.pdf');
    const configurationBytes = readFileSync(new URL(`../static${STUDY_CONFIGURATION_PATH}`, import.meta.url));
    const guideBytes = readFileSync(new URL(`../static${STUDY_GUIDE_PATH}`, import.meta.url));
    const envelope = decodeEnvelope(configurationBytes);
    const study = JSON.parse(new TextDecoder().decode(envelope.configurationBytes));
    const source = JSON.parse(readFileSync(new URL('../../researcher-tools/studies/phone-usage-five-day.json', import.meta.url), 'utf8'));
    expect(study).toEqual(source);
    expect(fingerprint(study.signer.public_key)).toBe('3E17 CD8A 4AD2 198D 87B7 81EA 9EBD 3201');
    expect(verify(envelope.configurationBytes, envelope.signature, study.signer.public_key)).toBe(true);
    expect(study.surveys[0].questions).toHaveLength(7);
    expect(study.surveys[0].questions[3].options.map((option: { id: string }) => option.id)).toEqual(['yes', 'none', 'unsure']);
    expect(study.surveys[0].questions[6].id).toBe('perceived-daily-impact');
    expect(guideBytes.subarray(0, 5).toString()).toBe('%PDF-');
    expect(guideBytes.length).toBeGreaterThan(10_000);
    expect(study.title).toBe(zhTW.study.title);
    expect(en.study.team).toContain(study.researcher.name);
    expect(zhTW.study.team).toContain(study.researcher.name);
    expect(study.minimum_client_version).toBe('41');
    expect(study.upload).toEqual({});
    expect(readdirSync(new URL(`../static${STUDY_CONFIGURATION_PATH}/..`, import.meta.url)).sort()).toEqual([
      STUDY_CONFIGURATION_PATH.split('/').at(-1),
      STUDY_GUIDE_PATH.split('/').at(-1)
    ].sort());
  });

  it('explains file import and manual return without disclosing the study schedule', () => {
    expect(en.study.importInstructions).toContain('Choose a study file');
    expect(zhTW.study.importInstructions).toContain('選擇研究設定檔');
    expect(en.study.deliveryInstructions).toContain('does not upload automatically');
    expect(zhTW.study.deliveryInstructions).toContain('不會自動上傳');
    for (const copy of [en.study, zhTW.study]) {
      expect(copy.deliveryInstructions).toContain('.partexp');
      expect(Object.values(copy).join(' ')).not.toMatch(/500|12[:：]00|17[:：]00|第\s*3|day\s*3|limited-500|baseline|kbps/i);
    }
  });
});

describe('participant upload disclosure', () => {
  it('keeps the installation code inside ciphertext in both locales', () => {
    expect(en.delivery.upload.code).toContain('after decrypting');
    expect(en.delivery.upload.metadata).toContain('cannot see your installation code');
    expect(zhTW.delivery.upload.code).toContain('解密後');
    expect(zhTW.delivery.upload.metadata).toContain('看不到安裝代碼');
  });
});

// The naming note is the one piece of copy whose first half is flattering and whose second half is
// not. Pinning the second half keeps a later edit from quietly dropping it and leaving a claim of
// participant control the app does not provide.
describe('participant naming note', () => {
  it('states the limits alongside the name in both locales', () => {
    expect(en.hero.naming.name).toContain('Latin');
    expect(en.hero.naming.limits).toContain('Nothing that has already left your phone can be taken back');
    expect(zhTW.hero.naming.name).toContain('拉丁文');
    expect(zhTW.hero.naming.limits).toContain('無法收回');
  });
});
