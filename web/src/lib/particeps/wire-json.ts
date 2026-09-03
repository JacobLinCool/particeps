/** Parse an embedded Protocol JSON field without requiring JCS member order or whitespace. */
export function parseStrictEmbeddedJson(text: string): unknown {
  try {
    new DuplicateMemberScanner(text).scan();
    return JSON.parse(text) as unknown;
  } catch (error) {
    if (error instanceof Error && error.message === 'embedded_json_duplicate_member') throw error;
    throw new Error('embedded_json_invalid');
  }
}

class DuplicateMemberScanner {
  private offset = 0;

  constructor(private readonly text: string) {}

  scan(): void {
    this.whitespace();
    this.value();
    this.whitespace();
    if (this.offset !== this.text.length) throw new Error('embedded_json_trailing');
  }

  private value(): void {
    this.whitespace();
    switch (this.text[this.offset]) {
      case '{': this.object(); return;
      case '[': this.array(); return;
      case '"': this.string(); return;
      case 't': this.literal('true'); return;
      case 'f': this.literal('false'); return;
      case 'n': this.literal('null'); return;
      default: this.number();
    }
  }

  private object(): void {
    this.offset += 1;
    this.whitespace();
    const names = new Set<string>();
    if (this.consume('}')) return;
    while (true) {
      this.whitespace();
      const name = this.string();
      if (names.has(name)) throw new Error('embedded_json_duplicate_member');
      names.add(name);
      this.whitespace();
      this.require(':');
      this.value();
      this.whitespace();
      if (this.consume('}')) return;
      this.require(',');
    }
  }

  private array(): void {
    this.offset += 1;
    this.whitespace();
    if (this.consume(']')) return;
    while (true) {
      this.value();
      this.whitespace();
      if (this.consume(']')) return;
      this.require(',');
    }
  }

  private string(): string {
    if (this.text[this.offset] !== '"') throw new Error('embedded_json_string');
    const start = this.offset++;
    while (this.offset < this.text.length) {
      const current = this.text.charCodeAt(this.offset++);
      if (current === 0x22) {
        return JSON.parse(this.text.slice(start, this.offset)) as string;
      }
      if (current === 0x5c) {
        if (this.offset >= this.text.length) throw new Error('embedded_json_escape');
        this.offset += 1;
      }
    }
    throw new Error('embedded_json_string');
  }

  private number(): void {
    const match = /-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/y;
    match.lastIndex = this.offset;
    const token = match.exec(this.text);
    if (!token) throw new Error('embedded_json_number');
    this.offset = match.lastIndex;
  }

  private literal(value: string): void {
    if (!this.text.startsWith(value, this.offset)) throw new Error('embedded_json_literal');
    this.offset += value.length;
  }

  private whitespace(): void {
    while (this.offset < this.text.length && /[\u0009\u000a\u000d\u0020]/.test(this.text[this.offset])) {
      this.offset += 1;
    }
  }

  private consume(value: string): boolean {
    if (this.text[this.offset] !== value) return false;
    this.offset += 1;
    return true;
  }

  private require(value: string): void {
    if (!this.consume(value)) throw new Error('embedded_json_structure');
  }
}
