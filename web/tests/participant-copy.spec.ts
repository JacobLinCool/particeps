import { describe, expect, it } from 'vitest';
import { ANDROID_APK_URL, GLANCE } from '$lib/participant/content';
import { en, zhTW } from '$lib/participant/copy';

describe('participant page structure', () => {
  it('links only to the retained overview sections', () => {
    expect(GLANCE.map(({ href }) => href)).toEqual(['#collect', '#where']);
    expect('fingerprint' in en).toBe(false);
    expect('controls' in en).toBe(false);
    expect('fingerprint' in zhTW).toBe(false);
    expect('controls' in zhTW).toBe(false);
  });

  it('offers the verified Android release from the hero in both locales', () => {
    expect(ANDROID_APK_URL).toBe(
      'https://github.com/JacobLinCool/particeps/releases/download/v1.0.0-rc.6/particeps-v1.0.0-rc.6.apk'
    );
    expect(en.hero.download).toBe('Download Android App');
    expect(zhTW.hero.download).toBe('下載 Android App');
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
