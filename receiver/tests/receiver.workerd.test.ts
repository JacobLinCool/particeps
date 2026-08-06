import { env, exports } from "cloudflare:workers";
import { describe, expect, it } from "vitest";
import corpus from "../../protocol/v1/conformance-vectors.json";
import { type ReceiverEnv } from "../src/contract";
import { handleRequest } from "../src/index";

const UPLOAD_URL = "https://receiver.example.test/v1/upload";
const RESEARCHER_KEY_ID = "vector-hpke";

describe("Particeps receiver in workerd", () => {
  it("stores and exactly replays the shared Protocol v1 bundle through native R2", async () => {
    const body = decodeHex(corpus.valid.bundle.container_hex);
    const receipt = corpus.valid.upload_receipt.value;
    const expectedReceipt = new TextDecoder().decode(
      decodeHex(corpus.valid.upload_receipt.canonical_jcs_utf8_hex),
    );

    const created = await exports.default.fetch(UPLOAD_URL, requestInit(body, receipt));
    const replayed = await exports.default.fetch(UPLOAD_URL, requestInit(body, receipt));
    const createdReceipt = await created.text();
    const replayedReceipt = await replayed.text();

    expect({ status: created.status, body: createdReceipt }).toEqual({
      status: 201,
      body: expectedReceipt,
    });
    expect({ status: replayed.status, body: replayedReceipt }).toEqual({
      status: 200,
      body: expectedReceipt,
    });

    const stored = await env.BUNDLES.get(receipt.bundle_id);
    expect(stored).not.toBeNull();
    expect(new Uint8Array(await stored!.arrayBuffer())).toEqual(body);
    const metadata = stored!.customMetadata;
    expect(metadata).toBeDefined();
    expect(metadata!).toMatchObject({
      sha256: receipt.sha256,
      byte_count: receipt.byte_count,
      configuration_sha256: receipt.configuration_sha256,
      researcher_key_id: RESEARCHER_KEY_ID,
      first_sequence_number: receipt.first_sequence_number,
      last_sequence_number: receipt.last_sequence_number,
      event_count: receipt.event_count,
    });
    expect(Object.keys(metadata!).sort()).toEqual([
      "byte_count",
      "configuration_sha256",
      "event_count",
      "first_sequence_number",
      "last_sequence_number",
      "received_at_utc",
      "researcher_key_id",
      "sha256",
    ]);
    expect(metadata!.received_at_utc).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/,
    );
  });

  it("drains and verifies a replay when the real R2 conditional create loses a race", async () => {
    const body = decodeHex(corpus.valid.bundle.container_hex);
    const receipt = corpus.valid.upload_receipt.value;
    const expectedReceipt = new TextDecoder().decode(
      decodeHex(corpus.valid.upload_receipt.canonical_jcs_utf8_hex),
    );
    const prepared = await exports.default.fetch(UPLOAD_URL, requestInit(body, receipt));
    expect([200, 201]).toContain(prepared.status);

    let hideFirstHead = true;
    const racingBucket = new Proxy(env.BUNDLES, {
      get(target, property) {
        if (property === "head") {
          return (key: string): Promise<R2Object | null> => {
            if (hideFirstHead) {
              hideFirstHead = false;
              return Promise.resolve(null);
            }
            return target.head(key);
          };
        }
        const value: unknown = Reflect.get(target, property);
        return typeof value === "function" ? value.bind(target) : value;
      },
    });
    const receiverEnv: ReceiverEnv = {
      BUNDLES: racingBucket,
      UPLOAD_PATH: env.UPLOAD_PATH,
      ALLOWED_CONFIGURATION_SHA256: env.ALLOWED_CONFIGURATION_SHA256,
      ALLOWED_RESEARCHER_KEY_ID: env.ALLOWED_RESEARCHER_KEY_ID,
    };

    const replayed = await handleRequest(
      new Request(UPLOAD_URL, requestInit(body, receipt)),
      receiverEnv,
    );

    expect(hideFirstHead).toBe(false);
    expect({ status: replayed.status, body: await replayed.text() }).toEqual({
      status: 200,
      body: expectedReceipt,
    });
  });
});

function requestInit(
  body: Uint8Array,
  receipt: typeof corpus.valid.upload_receipt.value,
): RequestInit {
  return {
    method: "POST",
    headers: {
      "Content-Type": "application/vnd.particeps.research-bundle",
      "Content-Length": receipt.byte_count,
      "Content-Digest": `sha-256=:${base64(decodeHex(receipt.sha256))}:`,
      "X-Particeps-Bundle-Format": "particeps-research-bundle-v1",
      "X-Particeps-Bundle-Id": receipt.bundle_id,
      "X-Particeps-Configuration-SHA256": receipt.configuration_sha256,
      "X-Particeps-Researcher-Key-Id": RESEARCHER_KEY_ID,
      "X-Particeps-Sequence-From": receipt.first_sequence_number,
      "X-Particeps-Sequence-To": receipt.last_sequence_number,
      "X-Particeps-Event-Count": receipt.event_count,
    },
    body,
  };
}

function decodeHex(hex: string): Uint8Array {
  if (hex.length % 2 !== 0 || !/^[0-9a-f]*$/.test(hex)) throw new Error("Invalid fixture hex");
  const bytes = new Uint8Array(hex.length / 2);
  for (let index = 0; index < bytes.length; index += 1) {
    bytes[index] = Number.parseInt(hex.slice(index * 2, index * 2 + 2), 16);
  }
  return bytes;
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}
