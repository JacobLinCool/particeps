/**
 * The four files a researcher leaves with, and how each one reaches the disk.
 *
 * The canonical JSON is not a nicety. `researcher-tools decrypt --config` takes the canonical
 * configuration, not the envelope, and no CLI command extracts one from an `.adccfg`: a researcher
 * who downloads only the signed file cannot decrypt their own data.
 *
 * Filenames come from the catalogue, which holds them as identifiers rather than prose — they are
 * typed into `researcher-tools sign --private …`, so they are the same in both languages.
 *
 * The two private keys are the exception, and only because their names are no longer a constant.
 * `signer.key_id` and `export.researcher_key_id` are derived from the key material, so a key file
 * can be named after the key inside it: `signer-07lsv3az679fg-private.key` names the kind, the key,
 * and the secrecy, and it contains verbatim the string `researcher-tools sign --key-id` wants. The
 * catalogue entries stay as the *kind* name, which is what the import targets and the disabled
 * tiles show — a tile is disabled exactly when no key is held, and a key that is not held has no
 * name.
 */

import type { Messages } from '$lib/i18n/types';
import type { Destination, Secrecy } from '$lib/ui/types';
import type { IconRef } from '$lib/ui/icons';

export type ArtifactId = 'signing-private' | 'hpke-private' | 'canonical' | 'adccfg';

/** The two derived key names, as the document carries them. `''` means no key is held. */
export interface ArtifactNames {
  signerKeyId: string;
  exportKeyId: string;
}

export interface ArtifactDefinition {
  id: ArtifactId;
  destination: Destination;
  secrecy: Secrecy;
  icon: IconRef;
  filename(m: Messages, names: ArtifactNames): string;
  mime: string;
}

export const ARTIFACTS: readonly ArtifactDefinition[] = [
  {
    id: 'signing-private',
    destination: 'hold',
    secrecy: 'secret',
    icon: 'key-sign',
    filename: (m, names) =>
      names.signerKeyId ? `${names.signerKeyId}-private.key` : m.file.signingPrivate,
    mime: 'text/plain'
  },
  {
    id: 'hpke-private',
    destination: 'hold',
    secrecy: 'secret',
    icon: 'key-open',
    filename: (m, names) =>
      names.exportKeyId ? `${names.exportKeyId}-private.key` : m.file.exportPrivate,
    mime: 'text/plain'
  },
  {
    id: 'canonical',
    destination: 'store',
    secrecy: 'archive',
    icon: 'json',
    filename: (m) => m.file.canonical,
    mime: 'application/json'
  },
  {
    id: 'adccfg',
    destination: 'send',
    secrecy: 'distribute',
    icon: 'package',
    filename: (m) => m.file.signed,
    mime: 'application/octet-stream'
  }
];

/** The name this artefact lands on disk under. `''` for an id that has no definition, which none
 *  has: the fallback exists so no caller needs a non-null assertion to ask a simple question. */
export function artifactFilename(id: ArtifactId, m: Messages, names: ArtifactNames): string {
  const definition = ARTIFACTS.find((artifact) => artifact.id === id);
  return definition ? definition.filename(m, names) : '';
}

/**
 * A Blob and an object URL, revoked as soon as the click has been dispatched. Nothing is uploaded
 * and nothing is kept: the URL exists for one turn of the event loop and the bytes live only as
 * long as the tab does.
 */
export function download(bytes: Uint8Array, filename: string, mime: string): void {
  const url = URL.createObjectURL(new Blob([bytes as BlobPart], { type: mime }));
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.rel = 'noopener';
  anchor.click();
  // Revoking synchronously races the download in Safari; a task later is after the fetch started.
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

const encoder = new TextEncoder();

/** What each artefact is made of, as bytes. `null` means it does not exist yet. */
export interface ArtifactSource {
  /** Raw 32-byte Ed25519 secret, canonical unpadded base64url. */
  signingPrivate: string | null;
  /** Raw 32-byte X25519 secret, canonical unpadded base64url. */
  hpkePrivate: string | null;
  canonical: Uint8Array;
  envelope: Uint8Array | null;
}

export function artifactBytes(id: ArtifactId, source: ArtifactSource): Uint8Array | null {
  switch (id) {
    case 'signing-private':
      return source.signingPrivate === null ? null : encoder.encode(source.signingPrivate);
    case 'hpke-private':
      return source.hpkePrivate === null ? null : encoder.encode(source.hpkePrivate);
    case 'canonical':
      return source.envelope === null ? null : source.canonical;
    case 'adccfg':
      return source.envelope;
  }
}
