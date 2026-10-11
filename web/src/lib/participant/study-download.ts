/** Mutable study URLs must be fetched anew rather than reusing a browser-cached configuration. */
export async function fetchStudyFile(url: string): Promise<Blob> {
  const response = await fetch(url, { cache: 'no-store', signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error('Study download failed');
  const file = await response.blob();
  if (file.size === 0) throw new Error('Study download is empty');
  return file;
}
