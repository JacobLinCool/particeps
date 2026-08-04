/** Private-key imports for the researcher workspace.
 *
 * Both artifacts are one canonical unpadded base64url string containing 32 raw bytes. Public keys
 * are always derived locally; a second value beside the secret is never trusted.
 */

export {
  hpkeKeyPairFromPrivate,
  signingKeyPairFromPrivate,
  type HpkeKeyPair,
  type SigningKeyPair
} from '$lib/adc/crypto';
