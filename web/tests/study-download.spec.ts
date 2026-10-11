import { afterEach, describe, expect, it, vi } from 'vitest';
import { fetchStudyFile } from '$lib/participant/study-download';

afterEach(() => vi.unstubAllGlobals());

describe('current study download', () => {
  it('requests fresh bytes at the same URL on each download', async () => {
    const request = vi.fn()
      .mockResolvedValueOnce(new Response('first signed study'))
      .mockResolvedValueOnce(new Response('updated signed study'));
    vi.stubGlobal('fetch', request);
    const path = '/particeps/studies/phone-usage-five-day/study.partcfg';
    expect(await (await fetchStudyFile(path)).text()).toBe('first signed study');
    expect(await (await fetchStudyFile(path)).text()).toBe('updated signed study');
    expect(request).toHaveBeenCalledTimes(2);
    for (const [url, options] of request.mock.calls) {
      expect(url).toBe(path);
      expect(options.cache).toBe('no-store');
      expect(options.signal).toBeInstanceOf(AbortSignal);
    }
  });

  it.each([404, 500])('rejects HTTP %s without returning an old file', async (status) => {
    const request = vi.fn().mockResolvedValue(new Response('error', { status }));
    vi.stubGlobal('fetch', request);
    await expect(fetchStudyFile('/study.partcfg')).rejects.toThrow('Study download failed');
    expect(request).toHaveBeenCalledTimes(1);
  });

  it('rejects empty files and network failures', async () => {
    const request = vi.fn().mockResolvedValueOnce(new Response(''))
      .mockRejectedValueOnce(new TypeError('offline'));
    vi.stubGlobal('fetch', request);
    await expect(fetchStudyFile('/study.partcfg')).rejects.toThrow('empty');
    await expect(fetchStudyFile('/study.partcfg')).rejects.toThrow('offline');
  });
});
