import { describe, expect, it } from 'vitest';
import { en, zhTW } from '$lib/participant/copy';

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
