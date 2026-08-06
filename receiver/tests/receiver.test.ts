import { describe, expect, it } from "vitest";
import corpus from "../../protocol/v1/conformance-vectors.json";
import {
  BUNDLE_FORMAT,
  BUNDLE_MEDIA_TYPE,
  PARTICEPS_HEADERS,
  CUSTOM_METADATA_KEYS,
  MAXIMUM_BODY_BYTES,
  bytesToHex,
  parseDeployment,
  parseUploadRequest,
  type ReceiverEnv,
} from "../src/contract";
import { handleRequest, type ReceiverDependencies } from "../src/index";
import type { DigestSink } from "../src/verified-body";

// Node does not brand fixed-length streams. The real workerd suite exercises Cloudflare's native
// implementation; the fast fake-R2 suite only needs an identity transform around the verifier.
class TestFixedLengthStream extends TransformStream<Uint8Array, Uint8Array> {
  constructor(_expectedLength: number) {
    super();
  }
}
Object.defineProperty(globalThis, "FixedLengthStream", { value: TestFixedLengthStream });

const BUNDLE_ID = "550e8400-e29b-41d4-a716-446655440000";
const OTHER_BUNDLE_ID = "f47ac10b-58cc-4372-a567-0e02b2c3d479";
const CONFIGURATION_SHA256 = "ab".repeat(32);
const OTHER_CONFIGURATION_SHA256 = "cd".repeat(32);
const RESEARCHER_KEY_ID = "researcher-key";
const UPLOAD_URL = "https://receiver.example.test/v1/upload";
const RECEIVED_AT = "2026-08-04T01:02:03.004Z";

const DEPENDENCIES: ReceiverDependencies = {
  createDigest: createTestDigest,
  now: () => new Date(RECEIVED_AT),
};

describe("Particeps Protocol v1 ciphertext receiver", () => {
  it("accepts exactly the request vocabulary the shared corpus fixes", () => {
    // The producer is Kotlin. Asserting against this Worker's own constants would pass however
    // either side were spelled, so both sides assert against the corpus instead.
    const request = corpus.valid.upload_request;
    expect(BUNDLE_MEDIA_TYPE).toBe(request.media_type);
    expect(BUNDLE_FORMAT).toBe(request.bundle_format);
    expect([...PARTICEPS_HEADERS].sort()).toEqual(
      request.routing_headers.map((name) => name.toLowerCase()).sort(),
    );
  });

  it("streams a new bundle into a create-only R2 object and returns the canonical receipt", async () => {
    const bucket = new FakeR2Bucket();
    const fixture = await requestFixture();

    const response = await handleRequest(fixture.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(201);
    expect(response.headers.get("content-type")).toBe("application/json");
    expect(await response.text()).toBe(canonicalReceipt(fixture));
    expect(bucket.putCalls).toBe(1);
    expect(bucket.lastOnlyIf?.get("if-none-match")).toBe("*");
    expect(bucket.lastPutBody).toEqual(fixture.body);
    expect(bucket.lastExpectedSha256).toBe(fixture.sha256);

    const stored = bucket.record(BUNDLE_ID);
    expect(stored).toBeDefined();
    expect(stored?.object.key).toBe(BUNDLE_ID);
    expect(stored?.object.size).toBe(fixture.body.length);
    expect(stored?.object.httpMetadata?.contentType).toBe(BUNDLE_MEDIA_TYPE);
    expect(Object.keys(stored?.object.customMetadata ?? {})).toEqual([...CUSTOM_METADATA_KEYS]);
    expect(stored?.object.customMetadata).toEqual({
      sha256: fixture.sha256,
      byte_count: String(fixture.body.length),
      configuration_sha256: CONFIGURATION_SHA256,
      researcher_key_id: RESEARCHER_KEY_ID,
      first_sequence_number: "1",
      last_sequence_number: "1",
      event_count: "1",
      received_at_utc: RECEIVED_AT,
    });
  });

  it("returns the identical receipt for a fully verified exact replay without overwriting", async () => {
    const bucket = new FakeR2Bucket();
    const first = await requestFixture();
    const replay = await requestFixture();

    const created = await handleRequest(first.request, environment(bucket), DEPENDENCIES);
    const originalRecord = bucket.record(BUNDLE_ID);
    const repeated = await handleRequest(replay.request, environment(bucket), {
      ...DEPENDENCIES,
      now: () => new Date("2030-01-01T00:00:00.000Z"),
    });

    expect(created.status).toBe(201);
    expect(repeated.status).toBe(200);
    expect(await repeated.text()).toBe(await created.text());
    expect(bucket.putCalls).toBe(1);
    expect(bucket.record(BUNDLE_ID)).toBe(originalRecord);
    expect(bucket.record(BUNDLE_ID)?.object.customMetadata?.received_at_utc).toBe(RECEIVED_AT);
  });

  it("resolves concurrent identical creates as one 201 and one 200", async () => {
    const bucket = new FakeR2Bucket();
    const left = await requestFixture();
    const right = await requestFixture();

    const responses = await Promise.all([
      handleRequest(left.request, environment(bucket), DEPENDENCIES),
      handleRequest(right.request, environment(bucket), DEPENDENCIES),
    ]);

    expect(responses.map((response) => response.status).sort()).toEqual([200, 201]);
    expect(await responses[0]!.text()).toBe(await responses[1]!.text());
    expect(bucket.objects.size).toBe(1);
    expect(bucket.putCalls).toBe(2);
  });

  it("validates the body when a raced conditional write declines it without reading", async () => {
    const bucket = new FakeR2Bucket();
    const original = await requestFixture();
    expect((await handleRequest(original.request, environment(bucket), DEPENDENCIES)).status).toBe(201);

    bucket.hideExistingFromNextHead = true;
    bucket.declineExistingBeforeRead = true;
    const response = await handleRequest(
      (await requestFixture()).request,
      environment(bucket),
      DEPENDENCIES,
    );

    expect(response.status).toBe(200);
    expect(bucket.declinedBeforeRead).toBe(true);
    expect(await response.text()).toBe(canonicalReceipt(original));
  });

  it("accepts documented OkHttp and Cloudflare transport headers without treating them as claims", async () => {
    const bucket = new FakeR2Bucket();
    const fixture = await requestFixture({
      headers: {
        "Accept-Encoding": "br, gzip",
        "CF-Connecting-IP": "192.0.2.1",
        "CF-EW-Via": "15",
        "CF-Ray": "230b030023ae2822-SJC",
        "CF-Visitor": "{\"scheme\":\"https\"}",
        "CDN-Loop": "cloudflare",
        Connection: "Keep-Alive",
        "MF-Original-Hostname": "localhost",
        "User-Agent": "okhttp/5",
        "X-Forwarded-For": "192.0.2.1",
        "X-Forwarded-Proto": "https",
      },
    });

    expect((await handleRequest(fixture.request, environment(bucket), DEPENDENCIES)).status).toBe(201);
  });

  it("never overwrites a reused bundle ID with different ciphertext", async () => {
    const bucket = new FakeR2Bucket();
    const original = await requestFixture();
    const changedBody = original.body.slice();
    changedBody[changedBody.length - 1] = changedBody[changedBody.length - 1]! ^ 0xff;

    expect((await handleRequest(original.request, environment(bucket), DEPENDENCIES)).status).toBe(201);
    const storedBefore = bucket.record(BUNDLE_ID)?.body.slice();
    const conflict = await requestFixture({ body: changedBody });
    const response = await handleRequest(conflict.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(409);
    expect(await response.text()).toBe("bundle_conflict\n");
    expect(bucket.record(BUNDLE_ID)?.body).toEqual(storedBefore);
  });

  it("verifies replay bytes instead of trusting a repeated digest header", async () => {
    const bucket = new FakeR2Bucket();
    const original = await requestFixture();
    expect((await handleRequest(original.request, environment(bucket), DEPENDENCIES)).status).toBe(201);

    const changedBody = original.body.slice();
    changedBody[changedBody.length - 1] = changedBody[changedBody.length - 1]! ^ 0xff;
    const forgedReplay = await requestFixture({ body: changedBody, digestBody: original.body });
    const response = await handleRequest(forgedReplay.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(400);
    expect(bucket.putCalls).toBe(1);
  });

  it("treats a replay with different claimed range metadata as a conflict", async () => {
    const bucket = new FakeR2Bucket();
    const original = await requestFixture();
    expect((await handleRequest(original.request, environment(bucket), DEPENDENCIES)).status).toBe(201);
    const replay = await requestFixture({
      headers: {
        "X-Particeps-Sequence-From": "2",
        "X-Particeps-Sequence-To": "2",
      },
    });

    expect((await handleRequest(replay.request, environment(bucket), DEPENDENCIES)).status).toBe(409);
  });

  it("does not resolve success until the durable R2 write promise resolves", async () => {
    const bucket = new FakeR2Bucket();
    bucket.pauseBeforeCommit();
    const fixture = await requestFixture();
    let settled = false;

    const pending = handleRequest(fixture.request, environment(bucket), DEPENDENCIES)
      .finally(() => { settled = true; });
    await bucket.putReachedCommit;
    await Promise.resolve();
    expect(settled).toBe(false);

    bucket.releaseCommit();
    expect((await pending).status).toBe(201);
  });

  it("returns 503 on R2 failures and never fabricates a receipt", async () => {
    const headFailure = new FakeR2Bucket();
    headFailure.failHead = true;
    expect((await handleRequest(
      (await requestFixture()).request,
      environment(headFailure),
      DEPENDENCIES,
    )).status).toBe(503);

    const putFailure = new FakeR2Bucket();
    putFailure.failPut = true;
    const response = await handleRequest(
      (await requestFixture()).request,
      environment(putFailure),
      DEPENDENCIES,
    );
    expect(response.status).toBe(503);
    expect(putFailure.objects.size).toBe(0);
  });

  it("exposes only the deployment-fixed POST path", async () => {
    const bucket = new FakeR2Bucket();
    const wrongPath = await requestFixture({ url: "https://receiver.example.test/other" });
    const query = await requestFixture({ url: `${UPLOAD_URL}?download=1` });
    const get = new Request(UPLOAD_URL, { method: "GET" });

    expect((await handleRequest(wrongPath.request, environment(bucket), DEPENDENCIES)).status).toBe(404);
    expect((await handleRequest(query.request, environment(bucket), DEPENDENCIES)).status).toBe(404);
    const methodResponse = await handleRequest(get, environment(bucket), DEPENDENCIES);
    expect(methodResponse.status).toBe(405);
    expect(methodResponse.headers.get("allow")).toBe("POST");
    expect(bucket.headCalls).toBe(0);
  });

  it("fails closed when deployment inputs are placeholders or malformed", async () => {
    const bucket = new FakeR2Bucket();
    const fixture = await requestFixture();
    const env = environment(bucket);
    env.ALLOWED_CONFIGURATION_SHA256 = "REPLACE_WITH_64_LOWERCASE_HEX";

    const response = await handleRequest(fixture.request, env, DEPENDENCIES);

    expect(response.status).toBe(500);
    expect(await response.text()).toBe("receiver_misconfigured\n");
    expect(bucket.headCalls).toBe(0);
  });

  it("rejects missing and duplicate protocol headers", async () => {
    const missingBucket = new FakeR2Bucket();
    const missing = await requestFixture();
    missing.request.headers.delete("content-digest");
    expect((await handleRequest(missing.request, environment(missingBucket), DEPENDENCIES)).status).toBe(400);

    const duplicateBucket = new FakeR2Bucket();
    const duplicate = await requestFixture();
    duplicate.request.headers.append("x-particeps-event-count", "1");
    expect((await handleRequest(
      duplicate.request,
      environment(duplicateBucket),
      DEPENDENCIES,
    )).status).toBe(400);
    expect(missingBucket.headCalls + duplicateBucket.headCalls).toBe(0);
  });

  it("returns 413 before storage when the declared body exceeds 32 MiB", async () => {
    const bucket = new FakeR2Bucket();
    const fixture = await requestFixture({
      headers: { "Content-Length": String(MAXIMUM_BODY_BYTES + 1) },
    });

    const response = await handleRequest(fixture.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(413);
    expect(await response.text()).toBe("payload_too_large\n");
    expect(bucket.headCalls).toBe(0);
  });

  it.each([
    ["media type parameter", { headers: { "Content-Type": `${BUNDLE_MEDIA_TYPE}; charset=binary` } }],
    ["transfer encoding", { headers: { "Transfer-Encoding": "chunked" } }],
    ["unknown Particeps header", { headers: { "X-Particeps-Extra": "value" } }],
    // Retired-identity rejection fixtures. The pre-rename Android Data Collector vocabulary is not
    // an older dialect of Protocol v1; an X-ADC-* name is absent from the closed-world header set
    // and is refused by the header check before any value is looked at.
    ["retired X-ADC-Extra header", { headers: { "X-ADC-Extra": "value" } }],
    ["retired X-ADC-Bundle-Format header", { headers: { "X-ADC-Bundle-Format": "research-bundle-v1" } }],
    ["unknown content header", { headers: { "Content-Language": "en" } }],
    ["authorization header", { headers: { Authorization: "Bearer ignored-is-not-allowed" } }],
    ["noncanonical byte count", { headers: { "Content-Length": "0256" } }],
    // Retired-identity rejection fixture: the current header name carrying the retired format
    // value, refused by the exact format comparison rather than accepted as a legacy spelling.
    ["retired bundle format value", { headers: { "X-Particeps-Bundle-Format": "research-bundle-v1" } }],
    ["unknown bundle format", { headers: { "X-Particeps-Bundle-Format": "research-bundle-v2" } }],
    // Retired-identity rejection fixture: the retired upload media type, refused by the exact
    // Content-Type comparison.
    ["retired bundle media type", { headers: { "Content-Type": "application/vnd.adc.research-bundle" } }],
    ["non-v4 bundle UUID", { headers: { "X-Particeps-Bundle-Id": "550e8400-e29b-11d4-a716-446655440000" } }],
    ["uppercase digest", { headers: { "X-Particeps-Configuration-SHA256": CONFIGURATION_SHA256.toUpperCase() } }],
    ["invalid researcher key", { headers: { "X-Particeps-Researcher-Key-Id": "Researcher_Key" } }],
    ["leading-zero sequence", { headers: { "X-Particeps-Sequence-From": "01" } }],
    ["empty event range", { headers: { "X-Particeps-Event-Count": "0" } }],
    ["range/count mismatch", { headers: { "X-Particeps-Sequence-To": "2" } }],
    ["configuration not allowed", { headers: { "X-Particeps-Configuration-SHA256": OTHER_CONFIGURATION_SHA256 } }],
    ["key not allowed", { headers: { "X-Particeps-Researcher-Key-Id": "different-key" } }],
    ["malformed content digest", { headers: { "Content-Digest": `SHA-256=:${"A".repeat(43)}=:` } }],
  ])("rejects %s before writing", async (_name, options) => {
    const bucket = new FakeR2Bucket();
    const fixture = await requestFixture(options);

    const response = await handleRequest(fixture.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(400);
    expect(bucket.putCalls).toBe(0);
  });

  // A retired header name and a retired header value are both `invalid_request` over HTTP, so the
  // table above cannot show that they fail different checks. Pin the reasons at the parser.
  it("refuses a retired header name and a retired header value on separate checks", async () => {
    const deployment = parseDeployment(environment(new FakeR2Bucket()));
    const retiredName = (await requestFixture({
      headers: { "X-ADC-Bundle-Format": "research-bundle-v1" },
    })).request;
    const retiredValue = (await requestFixture({
      headers: { "X-Particeps-Bundle-Format": "research-bundle-v1" },
    })).request;

    expect(() => parseUploadRequest(retiredName, deployment)).toThrow("Unknown request header");
    expect(() => parseUploadRequest(retiredValue, deployment)).toThrow("Bundle format is invalid");
  });

  it.each([
    ["wrong magic", (body: Uint8Array) => { body[0] = body[0]! ^ 0xff; }],
    ["outer bundle ID mismatch", (body: Uint8Array) => writeUuid(body, 8, OTHER_BUNDLE_ID)],
    ["outer configuration mismatch", (body: Uint8Array) => writeHex(body, 24, OTHER_CONFIGURATION_SHA256)],
    ["outer key mismatch", (body: Uint8Array) => {
      body.set(new TextEncoder().encode("researcher-kex"), 70);
    }],
  ])("rejects %s without leaving an object", async (_name, mutate) => {
    const bucket = new FakeR2Bucket();
    const body = makeBundle();
    mutate(body);
    const fixture = await requestFixture({ body });

    const response = await handleRequest(fixture.request, environment(bucket), DEPENDENCIES);

    expect(response.status).toBe(400);
    expect(bucket.objects.size).toBe(0);
  });

  it("rejects truncation, excess bytes, and a digest mismatch", async () => {
    const cases = [
      await requestFixture({ body: makeBundle().subarray(0, 100) }),
      await requestFixture({ headers: { "Content-Length": String(makeBundle().length - 1) } }),
      await requestFixture({ headers: { "Content-Length": String(makeBundle().length + 1) } }),
      await requestFixture({ digestBody: new Uint8Array(makeBundle().length) }),
    ];

    for (const fixture of cases) {
      const bucket = new FakeR2Bucket();
      const response = await handleRequest(fixture.request, environment(bucket), DEPENDENCIES);
      expect(response.status).toBe(400);
      expect(bucket.objects.size).toBe(0);
    }
  });
});

interface FixtureOptions {
  body?: Uint8Array;
  digestBody?: Uint8Array;
  headers?: Record<string, string>;
  method?: string;
  url?: string;
}

interface RequestFixture {
  request: Request;
  body: Uint8Array;
  sha256: string;
  byteCountText: string;
  firstSequenceNumber: string;
  lastSequenceNumber: string;
  eventCount: string;
}

async function requestFixture(options: FixtureOptions = {}): Promise<RequestFixture> {
  const body = options.body?.slice() ?? makeBundle();
  const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", options.digestBody ?? body));
  const sha256 = bytesToHex(digest);
  const headers = new Headers({
    "Content-Type": BUNDLE_MEDIA_TYPE,
    "Content-Length": String(body.length),
    "Content-Digest": `sha-256=:${toBase64(digest)}:`,
    "X-Particeps-Bundle-Format": "particeps-research-bundle-v1",
    "X-Particeps-Bundle-Id": BUNDLE_ID,
    "X-Particeps-Configuration-SHA256": CONFIGURATION_SHA256,
    "X-Particeps-Researcher-Key-Id": RESEARCHER_KEY_ID,
    "X-Particeps-Sequence-From": "1",
    "X-Particeps-Sequence-To": "1",
    "X-Particeps-Event-Count": "1",
    ...options.headers,
  });
  const method = options.method ?? "POST";
  const request = new Request(options.url ?? UPLOAD_URL, {
    method,
    headers,
    ...(method === "GET" || method === "HEAD" ? {} : { body }),
  });
  return {
    request,
    body,
    sha256,
    byteCountText: headers.get("content-length")!,
    firstSequenceNumber: headers.get("x-particeps-sequence-from")!,
    lastSequenceNumber: headers.get("x-particeps-sequence-to")!,
    eventCount: headers.get("x-particeps-event-count")!,
  };
}

function makeBundle(): Uint8Array {
  const keyId = new TextEncoder().encode(RESEARCHER_KEY_ID);
  const body = new Uint8Array(256);
  for (let index = 0; index < body.length; index += 1) body[index] = index & 0xff;
  body.set(new TextEncoder().encode("PTCEXP01"), 0);
  writeUuid(body, 8, BUNDLE_ID);
  writeHex(body, 24, CONFIGURATION_SHA256);
  body[56] = 0;
  body[57] = keyId.length;
  body.set(keyId, 70);
  return body;
}

function writeUuid(target: Uint8Array, offset: number, uuid: string): void {
  writeHex(target, offset, uuid.replaceAll("-", ""));
}

function writeHex(target: Uint8Array, offset: number, hex: string): void {
  for (let index = 0; index < hex.length; index += 2) {
    target[offset + index / 2] = Number.parseInt(hex.slice(index, index + 2), 16);
  }
}

function toBase64(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function canonicalReceipt(fixture: RequestFixture): string {
  return `{"bundle_id":"${BUNDLE_ID}","byte_count":"${fixture.byteCountText}",`
    + `"configuration_sha256":"${CONFIGURATION_SHA256}","event_count":"${fixture.eventCount}",`
    + `"first_sequence_number":"${fixture.firstSequenceNumber}",`
    + `"last_sequence_number":"${fixture.lastSequenceNumber}","sha256":"${fixture.sha256}"}`;
}

function environment(bucket: FakeR2Bucket): ReceiverEnv {
  return {
    BUNDLES: bucket as unknown as R2Bucket,
    UPLOAD_PATH: "/v1/upload",
    ALLOWED_CONFIGURATION_SHA256: CONFIGURATION_SHA256,
    ALLOWED_RESEARCHER_KEY_ID: RESEARCHER_KEY_ID,
  };
}

function createTestDigest(): DigestSink {
  const chunks: Uint8Array[] = [];
  let resolveDigest!: (digest: ArrayBuffer) => void;
  let rejectDigest!: (error: unknown) => void;
  const digest = new Promise<ArrayBuffer>((resolve, reject) => {
    resolveDigest = resolve;
    rejectDigest = reject;
  });
  return {
    writable: new WritableStream<Uint8Array>({
      write(chunk) {
        chunks.push(chunk.slice());
      },
      async close() {
        resolveDigest(await crypto.subtle.digest("SHA-256", concatenate(chunks)));
      },
      abort(error) {
        rejectDigest(error);
      },
    }),
    digest,
  };
}

function concatenate(chunks: Uint8Array[]): Uint8Array {
  const result = new Uint8Array(chunks.reduce((size, chunk) => size + chunk.length, 0));
  let offset = 0;
  for (const chunk of chunks) {
    result.set(chunk, offset);
    offset += chunk.length;
  }
  return result;
}

interface StoredRecord {
  body: Uint8Array;
  object: R2Object;
}

class FakeR2Bucket {
  readonly objects = new Map<string, StoredRecord>();
  headCalls = 0;
  putCalls = 0;
  failHead = false;
  failPut = false;
  hideExistingFromNextHead = false;
  declineExistingBeforeRead = false;
  declinedBeforeRead = false;
  lastOnlyIf: Headers | undefined;
  lastPutBody: Uint8Array | undefined;
  lastExpectedSha256: string | undefined;

  private commitGate: Promise<void> | undefined;
  private resolveCommit: (() => void) | undefined;
  private resolvePutReached!: () => void;
  putReachedCommit: Promise<void> = new Promise((resolve) => { this.resolvePutReached = resolve; });

  record(key: string): StoredRecord | undefined {
    return this.objects.get(key);
  }

  pauseBeforeCommit(): void {
    this.commitGate = new Promise((resolve) => { this.resolveCommit = resolve; });
  }

  releaseCommit(): void {
    this.resolveCommit?.();
  }

  async head(key: string): Promise<R2Object | null> {
    this.headCalls += 1;
    if (this.failHead) throw new Error("R2 head failed");
    if (this.hideExistingFromNextHead) {
      this.hideExistingFromNextHead = false;
      return null;
    }
    return this.objects.get(key)?.object ?? null;
  }

  async put(key: string, value: ReadableStream, options: R2PutOptions): Promise<R2Object | null> {
    this.putCalls += 1;
    this.lastOnlyIf = options.onlyIf instanceof Headers ? options.onlyIf : undefined;
    if (this.failPut) throw new Error("R2 put failed");
    if (this.declineExistingBeforeRead && this.objects.has(key)) {
      this.declinedBeforeRead = true;
      return null;
    }
    const body = await readAll(value as ReadableStream<Uint8Array>);
    const digest = await crypto.subtle.digest("SHA-256", body);
    const digestHex = bytesToHex(new Uint8Array(digest));
    const expected = options.sha256;
    if (!(expected instanceof ArrayBuffer) || bytesToHex(new Uint8Array(expected)) !== digestHex) {
      throw new Error("R2 checksum mismatch");
    }
    this.lastPutBody = body;
    this.lastExpectedSha256 = digestHex;
    this.resolvePutReached();
    await this.commitGate;

    if (this.lastOnlyIf?.get("if-none-match") === "*" && this.objects.has(key)) return null;
    const object = fakeObject(key, body.length, digest, options);
    this.objects.set(key, { body, object });
    return object;
  }
}

async function readAll(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  while (true) {
    const next = await reader.read();
    if (next.done) break;
    chunks.push(next.value.slice());
  }
  return concatenate(chunks);
}

function fakeObject(key: string, size: number, digest: ArrayBuffer, options: R2PutOptions): R2Object {
  const httpMetadata = options.httpMetadata instanceof Headers
    ? {}
    : { ...(options.httpMetadata ?? {}) };
  return {
    key,
    version: "fake-version",
    size,
    etag: "fake-etag",
    httpEtag: "\"fake-etag\"",
    uploaded: new Date(RECEIVED_AT),
    httpMetadata,
    customMetadata: { ...(options.customMetadata ?? {}) },
    range: { offset: 0, length: size },
    checksums: {
      sha256: digest,
      toJSON: () => ({ sha256: bytesToHex(new Uint8Array(digest)) }),
    },
    storageClass: "Standard",
    writeHttpMetadata() {},
  };
}
