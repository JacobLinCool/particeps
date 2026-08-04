import {
  BUNDLE_MEDIA_TYPE,
  DeploymentViolation,
  PayloadTooLargeViolation,
  RequestViolation,
  encodeReceipt,
  isExactObject,
  objectMetadata,
  parseDeployment,
  parseUploadRequest,
  type ReceiverEnv,
  type UploadClaims,
} from "./contract";
import {
  VerifiedBody,
  createWorkerDigest,
  type DigestFactory,
} from "./verified-body";

export interface ReceiverDependencies {
  createDigest: DigestFactory;
  now: () => Date;
}

const PRODUCTION_DEPENDENCIES: ReceiverDependencies = {
  createDigest: createWorkerDigest,
  now: () => new Date(),
};

export async function handleRequest(
  request: Request,
  env: ReceiverEnv,
  dependencies: ReceiverDependencies = PRODUCTION_DEPENDENCIES,
): Promise<Response> {
  let deployment;
  try {
    deployment = parseDeployment(env);
  } catch (error) {
    if (error instanceof DeploymentViolation) return errorResponse(500, "receiver_misconfigured");
    return errorResponse(500, "receiver_failure");
  }

  const url = new URL(request.url);
  if (url.pathname !== deployment.uploadPath || url.search !== "") {
    return errorResponse(404, "not_found");
  }
  if (request.method !== "POST") {
    return errorResponse(405, "method_not_allowed", { Allow: "POST" });
  }
  if (request.body === null) return errorResponse(400, "invalid_request");

  let claims: UploadClaims;
  try {
    claims = parseUploadRequest(request, deployment);
  } catch (error) {
    if (error instanceof PayloadTooLargeViolation) return errorResponse(413, "payload_too_large");
    if (error instanceof RequestViolation) return errorResponse(400, "invalid_request");
    return errorResponse(500, "receiver_failure");
  }

  let existing: R2Object | null;
  try {
    existing = await env.BUNDLES.head(claims.bundleId);
  } catch {
    return errorResponse(503, "storage_unavailable");
  }

  const body = new VerifiedBody(request.body, claims, dependencies.createDigest);
  if (existing !== null) {
    const bodyError = await validateBody(body);
    if (bodyError !== undefined) return bodyError;
    return isExactObject(existing, claims)
      ? receiptResponse(200, claims)
      : errorResponse(409, "bundle_conflict");
  }

  // R2 accepts request/response bodies or the readable half of a FixedLengthStream. Keep the
  // verifier as the single source reader while giving R2 an explicitly sized streaming body.
  const fixedLength = new FixedLengthStream(claims.byteCount);
  const forwarding = body.stream.pipeTo(fixedLength.writable);
  void forwarding.catch(() => undefined);

  let stored: R2Object | null;
  try {
    stored = await env.BUNDLES.put(claims.bundleId, fixedLength.readable, {
      onlyIf: new Headers({ "If-None-Match": "*" }),
      httpMetadata: { contentType: BUNDLE_MEDIA_TYPE },
      customMetadata: objectMetadata(claims, dependencies.now().toISOString()),
      sha256: exactArrayBuffer(claims.sha256Bytes),
    });
  } catch {
    await releaseFixedLengthStream(fixedLength.readable, forwarding);
    const bodyError = await validateBody(body);
    return bodyError ?? errorResponse(503, "storage_unavailable");
  }

  // A conditional create may return without pulling the body. Cancelling the fixed-length
  // transport releases pipeTo(); VerifiedBody.complete() then drains and verifies the same
  // request source before replay success or conflict is decided.
  await releaseFixedLengthStream(fixedLength.readable, forwarding);
  const bodyError = await validateBody(body);
  if (bodyError !== undefined) return bodyError;
  if (stored !== null) {
    return isExactObject(stored, claims)
      ? receiptResponse(201, claims)
      : errorResponse(503, "storage_unavailable");
  }

  // A competing create won after our initial HEAD. R2 writes and metadata reads are strongly
  // consistent, so the winning object is now the single authority for replay vs conflict.
  try {
    const winner = await env.BUNDLES.head(claims.bundleId);
    if (winner === null) return errorResponse(503, "storage_unavailable");
    return isExactObject(winner, claims)
      ? receiptResponse(200, claims)
      : errorResponse(409, "bundle_conflict");
  } catch {
    return errorResponse(503, "storage_unavailable");
  }
}

async function releaseFixedLengthStream(
  readable: ReadableStream,
  forwarding: Promise<void>,
): Promise<void> {
  await readable.cancel().catch(() => undefined);
  await forwarding.catch(() => undefined);
}

async function validateBody(body: VerifiedBody): Promise<Response | undefined> {
  try {
    await body.complete();
    return undefined;
  } catch (error) {
    return error instanceof RequestViolation
      ? errorResponse(400, "invalid_request")
      : errorResponse(503, "storage_unavailable");
  }
}

function exactArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.slice().buffer;
}

function receiptResponse(status: 200 | 201, claims: UploadClaims): Response {
  return new Response(encodeReceipt(claims), {
    status,
    headers: {
      "Cache-Control": "no-store",
      "Content-Type": "application/json",
    },
  });
}

function errorResponse(status: number, code: string, headers?: HeadersInit): Response {
  const responseHeaders = new Headers(headers);
  responseHeaders.set("Cache-Control", "no-store");
  responseHeaders.set("Content-Type", "text/plain; charset=utf-8");
  return new Response(`${code}\n`, { status, headers: responseHeaders });
}

export default {
  fetch(request: Request, env: ReceiverEnv): Promise<Response> {
    return handleRequest(request, env);
  },
} satisfies ExportedHandler<ReceiverEnv>;
