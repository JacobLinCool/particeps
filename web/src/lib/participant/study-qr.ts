import { decodeEnvelope } from '$lib/particeps/envelope';
import { fingerprint, verify } from '$lib/particeps/crypto';
import { createJoinLink } from '$lib/particeps/join';

const SIGNER_PUBLIC_KEY = 'BkYy50nuq36nLAuMZMOXjR2KP0RRJxqPkvHKlHyg79s';

/** Bind the QR to the freshly downloaded study and the team's trusted signing key. */
export function studyJoinLink(url: string, bytes: Uint8Array): string {
  const envelope = decodeEnvelope(bytes);
  if (!verify(envelope.configurationBytes, envelope.signature, SIGNER_PUBLIC_KEY)) {
    throw new Error('Study signature invalid');
  }
  return createJoinLink(url, bytes, fingerprint(SIGNER_PUBLIC_KEY));
}
