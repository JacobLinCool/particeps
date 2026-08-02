/**
 * The slice of Node that `compat.spec.ts` uses.
 *
 * That test shells out to the `researcher-tools` CLI, which is the only way to compare this
 * encoder against the one the Android app runs. The project has no `@types/node` — nothing it
 * ships touches Node — and adding one for a single test would put a dependency in everyone's
 * install for a file that never reaches the browser. These are the five modules and eleven
 * functions that test calls, and nothing else.
 */

declare module 'node:child_process' {
  export function execFileSync(
    file: string,
    args: readonly string[],
    options?: { cwd?: string; encoding?: 'utf8'; stdio?: string | readonly string[] }
  ): string;
}

declare module 'node:fs' {
  export function existsSync(path: string): boolean;
  export function mkdirSync(path: string, options?: { recursive?: boolean }): void;
  export function mkdtempSync(prefix: string): string;
  export function readFileSync(path: string): Uint8Array;
  export function readFileSync(path: string, encoding: 'utf8'): string;
  export function rmSync(path: string, options?: { recursive?: boolean; force?: boolean }): void;
  export function writeFileSync(path: string, data: string | Uint8Array, encoding?: 'utf8'): void;
}

declare module 'node:os' {
  export function tmpdir(): string;
}

declare module 'node:path' {
  export function dirname(path: string): string;
  export function join(...segments: string[]): string;
}

declare module 'node:url' {
  export function fileURLToPath(url: string | URL): string;
}
