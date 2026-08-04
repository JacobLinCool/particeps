import {
  RequestViolation,
  bytesToHex,
  requiredOuterPrefixLength,
  verifyOuterPrefix,
  type UploadClaims,
} from "./contract";

export interface DigestSink {
  writable: WritableStream<ArrayBuffer | ArrayBufferView>;
  digest: Promise<ArrayBuffer>;
}

export type DigestFactory = () => DigestSink;

export function createWorkerDigest(): DigestSink {
  const stream = new crypto.DigestStream("SHA-256");
  return { writable: stream, digest: stream.digest };
}

/**
 * One-pass, bounded-memory verifier. R2 pulls from [stream]; if a conditional write declines to
 * consume all bytes, [complete] drains the same source without replaying or buffering it.
 */
export class VerifiedBody {
  readonly stream: ReadableStream<Uint8Array>;

  private readonly source: ReadableStreamDefaultReader<Uint8Array>;
  private readonly digestWriter: WritableStreamDefaultWriter<Uint8Array>;
  private readonly digest: Promise<ArrayBuffer>;
  private readonly prefix = new Uint8Array(134);
  private prefixLength = 0;
  private requiredPrefixLength = 58;
  private byteCount = 0;
  private finished = false;
  private outerVerified = false;
  private activeRead: Promise<void> | undefined;
  private failure: unknown;
  private outputCancelled = false;

  constructor(
    body: ReadableStream<Uint8Array>,
    private readonly claims: UploadClaims,
    createDigest: DigestFactory,
  ) {
    this.source = body.getReader();
    const digest = createDigest();
    this.digestWriter = digest.writable.getWriter();
    this.digest = digest.digest;
    // A protocol error can abort the digest before complete() needs its value. Observe that
    // rejection immediately; complete() still reports the original protocol failure.
    void this.digest.catch(() => undefined);
    this.stream = new ReadableStream<Uint8Array>(
      {
        pull: (controller) => this.pull(controller),
        // R2 may decline a conditional write without reading. Keep the request reader so
        // complete() can validate the exact replay body rather than trust its digest header.
        cancel: () => {
          this.outputCancelled = true;
        },
      },
      // Do not prefetch a request chunk before R2 asks for it. This keeps backpressure attached
      // to the durable sink and leaves complete() free to drain a declined conditional write.
      { highWaterMark: 0 },
    );
  }

  async complete(): Promise<void> {
    if (this.activeRead !== undefined) {
      await this.activeRead.catch(() => undefined);
    }
    while (!this.finished && this.failure === undefined) {
      await this.readOne();
    }
    if (this.failure !== undefined) throw this.failure;
    const actual = bytesToHex(new Uint8Array(await this.digest));
    if (actual !== this.claims.sha256) throw new RequestViolation("Body SHA-256 mismatch");
  }

  private pull(controller: ReadableStreamDefaultController<Uint8Array>): Promise<void> {
    const operation = this.readOne(controller);
    this.activeRead = operation;
    return operation.finally(() => {
      if (this.activeRead === operation) this.activeRead = undefined;
    });
  }

  private async readOne(controller?: ReadableStreamDefaultController<Uint8Array>): Promise<void> {
    if (this.finished) {
      if (!this.outputCancelled) controller?.close();
      return;
    }
    if (this.failure !== undefined) {
      if (!this.outputCancelled) controller?.error(this.failure);
      throw this.failure;
    }
    try {
      const next = await this.source.read();
      if (next.done) {
        this.finish();
        await this.digestWriter.close();
        if (!this.outputCancelled) controller?.close();
        return;
      }
      await this.accept(next.value);
      if (!this.outputCancelled) controller?.enqueue(next.value);
    } catch (error) {
      await this.fail(error);
      if (!this.outputCancelled) controller?.error(error);
      throw error;
    }
  }

  private async accept(chunk: Uint8Array): Promise<void> {
    this.byteCount += chunk.byteLength;
    if (this.byteCount > this.claims.byteCount) {
      throw new RequestViolation("Body exceeds Content-Length");
    }
    this.captureOuterPrefix(chunk);
    await this.digestWriter.write(chunk);
  }

  private captureOuterPrefix(chunk: Uint8Array): void {
    let chunkOffset = 0;
    while (this.prefixLength < this.requiredPrefixLength && chunkOffset < chunk.length) {
      const copied = Math.min(
        this.requiredPrefixLength - this.prefixLength,
        chunk.length - chunkOffset,
      );
      this.prefix.set(chunk.subarray(chunkOffset, chunkOffset + copied), this.prefixLength);
      this.prefixLength += copied;
      chunkOffset += copied;

      if (this.prefixLength === 58 && this.requiredPrefixLength === 58) {
        this.requiredPrefixLength = requiredOuterPrefixLength(this.prefix.subarray(0, 58));
      }
      if (this.prefixLength === this.requiredPrefixLength && !this.outerVerified) {
        verifyOuterPrefix(this.prefix.subarray(0, this.requiredPrefixLength), this.claims);
        this.outerVerified = true;
      }
    }
  }

  private finish(): void {
    if (this.byteCount !== this.claims.byteCount) {
      throw new RequestViolation("Body length does not match Content-Length");
    }
    if (!this.outerVerified) throw new RequestViolation("Bundle outer header is truncated");
    this.finished = true;
  }

  private async fail(error: unknown): Promise<void> {
    if (this.failure !== undefined) return;
    this.failure = error;
    await Promise.allSettled([
      this.source.cancel(error),
      this.digestWriter.abort(error),
    ]);
  }
}
