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
