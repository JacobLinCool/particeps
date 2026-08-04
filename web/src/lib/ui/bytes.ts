/**
 * Colouring for the byte pane.
 *
 * Three roles, deliberately fewer than an editor would use: keys, string
 * values, and numbers-and-booleans. A fourth colour would invite a fifth, and the pane would stop
 * being a view of the bytes and start being a place to edit them.
 */

export type ByteRole = 'key' | 'str' | 'num' | 'blob' | 'plain';

export interface ByteSpan {
  text: string;
  role: ByteRole;
}

/** Keys whose value is opaque. Everything here renders in --binary, collapsed to one line. */
export const OPAQUE_KEYS: readonly string[] = [];

/** Walks a JSON value from `start`, respecting string boundaries, and returns the next index. */
function endOfValue(text: string, start: number): number {
  const open = text[start];
  if (open !== '{' && open !== '[') {
    const match = /^(?:"(?:\\.|[^"\\])*"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?|true|false|null)/.exec(
      text.slice(start)
    );
    return start + (match?.[0].length ?? 1);
  }
  let depth = 0;
  let inString = false;
  for (let index = start; index < text.length; index += 1) {
    const character = text[index];
    if (inString) {
      if (character === '\\') index += 1;
      else if (character === '"') inString = false;
      continue;
    }
    if (character === '"') inString = true;
    else if (character === '{' || character === '[') depth += 1;
    else if (character === '}' || character === ']') {
      depth -= 1;
      if (depth === 0) return index + 1;
    }
  }
  return text.length;
}

function opaqueRanges(text: string, keys: readonly string[]): [number, number][] {
  const ranges: [number, number][] = [];
  for (const key of keys) {
    const pattern = new RegExp(`"${key}"\\s*:\\s*`, 'g');
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(text))) {
      const start = match.index + match[0].length;
      ranges.push([start, endOfValue(text, start)]);
    }
  }
  return ranges.sort((a, b) => a[0] - b[0]);
}

const TOKEN = /("(?:\\.|[^"\\])*")(\s*:)|("(?:\\.|[^"\\])*")|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)|\b(true|false|null)\b/g;

function tokenizeSegment(segment: string, out: ByteSpan[]): void {
  let cursor = 0;
  let match: RegExpExecArray | null;
  TOKEN.lastIndex = 0;
  while ((match = TOKEN.exec(segment))) {
    if (match.index > cursor) out.push({ text: segment.slice(cursor, match.index), role: 'plain' });
    if (match[1]) {
      out.push({ text: match[1], role: 'key' });
      out.push({ text: match[2], role: 'plain' });
    } else if (match[3]) out.push({ text: match[3], role: 'str' });
    else out.push({ text: match[4] ?? match[5], role: 'num' });
    cursor = match.index + match[0].length;
  }
  if (cursor < segment.length) out.push({ text: segment.slice(cursor), role: 'plain' });
}

export function tokenize(text: string, keys: readonly string[] = OPAQUE_KEYS): ByteSpan[] {
  const spans: ByteSpan[] = [];
  let cursor = 0;
  for (const [start, end] of opaqueRanges(text, keys)) {
    if (start < cursor) continue;
    tokenizeSegment(text.slice(cursor, start), spans);
    spans.push({ text: text.slice(start, end).replace(/\s+/g, ''), role: 'blob' });
    cursor = end;
  }
  tokenizeSegment(text.slice(cursor), spans);
  return spans;
}
