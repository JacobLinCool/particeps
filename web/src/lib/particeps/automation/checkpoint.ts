import { sha256 } from '@noble/hashes/sha2.js';
import type {
  AutomationCheckpoint,
  DurableTimer,
  ResourceKey,
  TimerTarget
} from './types';
import { resourceKeyString } from './types';

const PREFIX = 'automation-checkpoint-v1:';
const DOMAIN = 'particeps-automation-checkpoint-v1';
const encoder = new TextEncoder();
const decoder = new TextDecoder('utf-8', { fatal: true });

class Writer {
  readonly bytes: number[] = [];
  byte(value: number): void { this.bytes.push(value & 0xff); }
  boolean(value: boolean): void { this.byte(value ? 1 : 0); }
  int(value: number): void {
    if (!Number.isInteger(value) || value < -0x8000_0000 || value > 0x7fff_ffff) throw new Error('checkpoint_int');
    this.byte(value >>> 24); this.byte(value >>> 16); this.byte(value >>> 8); this.byte(value);
  }
  long(value: bigint | number): void {
    let integer = typeof value === 'bigint' ? value : BigInt(value);
    if (integer < -(1n << 63n) || integer >= (1n << 63n)) throw new Error('checkpoint_long');
    if (integer < 0n) integer += 1n << 64n;
    for (let shift = 56n; shift >= 0n; shift -= 8n) this.byte(Number((integer >> shift) & 0xffn));
  }
  string(value: string): void {
    const encoded = encoder.encode(value);
    if (encoded.length > 512 * 1024) throw new Error('checkpoint_string');
    this.int(encoded.length); this.bytes.push(...encoded);
  }
  nullableLong(value: bigint | number | null): void { this.boolean(value !== null); if (value !== null) this.long(value); }
  nullableString(value: string | null): void { this.boolean(value !== null); if (value !== null) this.string(value); }
  ulong(value: bigint): void { if (value < 0n || value >= (1n << 64n)) throw new Error('checkpoint_ulong'); this.string(String(value)); }
}

class Reader {
  private offset = 0;
  constructor(private readonly bytes: Uint8Array) {}
  private take(): number { if (this.offset >= this.bytes.length) throw new Error('checkpoint_truncated'); return this.bytes[this.offset++]; }
  byte(): number { return this.take(); }
  boolean(): boolean { const value = this.take(); if (value > 1) throw new Error('checkpoint_boolean'); return value === 1; }
  int(): number {
    const value = (this.take() * 0x1_000000) + (this.take() << 16) + (this.take() << 8) + this.take();
    return value >= 0x8000_0000 ? value - 0x1_0000_0000 : value;
  }
  long(): bigint {
    let value = 0n;
    for (let index = 0; index < 8; index += 1) value = (value << 8n) | BigInt(this.take());
    return value >= (1n << 63n) ? value - (1n << 64n) : value;
  }
  string(): string {
    const size = this.int();
    if (size < 0 || size > 512 * 1024 || this.offset + size > this.bytes.length) throw new Error('checkpoint_string');
    const value = decoder.decode(this.bytes.subarray(this.offset, this.offset + size));
    this.offset += size;
    return value;
  }
  nullableLong(): bigint | null { return this.boolean() ? this.long() : null; }
  nullableString(): string | null { return this.boolean() ? this.string() : null; }
  ulong(): bigint { const value = BigInt(this.string()); if (value < 0n || value >= (1n << 64n)) throw new Error('checkpoint_ulong'); return value; }
  done(): boolean { return this.offset === this.bytes.length; }
}

function sorted<T>(map: Map<string, T>): [string, T][] {
  return [...map].sort(([left], [right]) => left.localeCompare(right));
}

function writeMap<T>(writer: Writer, entries: readonly [string, T][], writeKey: (key: string) => void, writeValue: (value: T) => void): void {
  if (entries.length > 4096) throw new Error('checkpoint_collection');
  writer.int(entries.length);
  for (const [key, value] of entries) { writeKey(key); writeValue(value); }
}

function writeList<T>(writer: Writer, values: readonly T[], write: (value: T) => void): void {
  if (values.length > 4096) throw new Error('checkpoint_collection');
  writer.int(values.length); values.forEach(write);
}

function readMap<T>(reader: Reader, readKey: () => string, readValue: () => T): Map<string, T> {
  const size = reader.int();
  if (size < 0 || size > 4096) throw new Error('checkpoint_collection');
  const result = new Map<string, T>();
  for (let index = 0; index < size; index += 1) {
    const key = readKey(); if (result.has(key)) throw new Error('checkpoint_duplicate_key'); result.set(key, readValue());
  }
  return result;
}

function readList<T>(reader: Reader, read: () => T): T[] {
  const size = reader.int(); if (size < 0 || size > 4096) throw new Error('checkpoint_collection');
  return Array.from({ length: size }, read);
}

function writeTarget(writer: Writer, target: TimerTarget): void {
  switch (target.type) {
    case 'CALENDAR_UTC': writer.byte(0); writer.long(target.utc_millis); break;
    case 'ACTIVE_ELAPSED': writer.byte(1); writer.long(target.elapsed_nanos); break;
    case 'SAME_BOOT_MONOTONIC': writer.byte(2); writer.string(target.boot_session_id); writer.long(target.elapsed_realtime_nanos); break;
  }
}

function readTarget(reader: Reader): TimerTarget {
  switch (reader.byte()) {
    case 0: return { type: 'CALENDAR_UTC', utc_millis: Number(reader.long()) };
    case 1: return { type: 'ACTIVE_ELAPSED', elapsed_nanos: reader.long() };
    case 2: return { type: 'SAME_BOOT_MONOTONIC', boot_session_id: reader.string(), elapsed_realtime_nanos: reader.long() };
    default: throw new Error('checkpoint_timer_target');
  }
}

function writeTimer(writer: Writer, timer: DurableTimer): void {
  writer.string(timer.id); writer.string(timer.automation_id); writer.ulong(timer.generation);
  writer.long(timer.causal_sequence); writer.string(timer.producer_key); writeTarget(writer, timer.target);
  writer.nullableLong(timer.logical_deadline_utc_millis); writer.nullableLong(timer.expires_at_utc_millis);
}

function readTimer(reader: Reader): DurableTimer {
  return {
    id: reader.string(), automation_id: reader.string(), generation: reader.ulong(),
    causal_sequence: Number(reader.long()), producer_key: reader.string(), target: readTarget(reader),
    logical_deadline_utc_millis: nullableNumber(reader.nullableLong()),
    expires_at_utc_millis: nullableNumber(reader.nullableLong())
  };
}

function nullableNumber(value: bigint | null): number | null {
  if (value === null) return null;
  const number = Number(value); if (!Number.isSafeInteger(number)) throw new Error('checkpoint_unsafe_number'); return number;
}

function resourceSortKey(key: ResourceKey): string { return `${key.kind.toLowerCase()}\0${key.id}`; }

export function encodeAutomationCheckpoint(checkpoint: AutomationCheckpoint): string {
  const writer = new Writer();
  writer.int(1); writer.long(checkpoint.evaluated_through_sequence); writer.string(checkpoint.lifecycle);
  writer.nullableLong(checkpoint.study_start_utc_millis); writer.long(checkpoint.last_active_elapsed_nanos);
  writer.long(checkpoint.last_calendar_elapsed_nanos);
  writeMap(writer, sorted(checkpoint.latch_values), (key) => writer.string(key), (value) => writer.boolean(value));
  writeMap(writer, sorted(checkpoint.presence_keys), (key) => writer.string(key), (values) =>
    writeList(writer, [...values].sort(), (value) => writer.string(value)));
  writeMap(writer, sorted(checkpoint.held_since_nanos), (key) => writer.string(key), (value) => writer.long(value));
  writeMap(writer, sorted(checkpoint.prior_condition_values), (key) => writer.string(key), (value) => writer.boolean(value));
  writeMap(writer, sorted(checkpoint.windows), (key) => writer.string(key), (entries) => writeList(writer, entries, (entry) => {
    writer.long(entry.sequence_number); writer.long(entry.time_nanos); writer.string(entry.boot_session_id); writer.string(String(entry.numeric_value));
  }));
  writeMap(writer, sorted(checkpoint.sequences), (key) => writer.string(key), (partials) => writeList(writer, partials, (partial) => {
    writer.int(partial.next_step); writer.long(partial.first_sequence_number); writer.long(partial.last_sequence_number);
    writer.long(partial.first_time_nanos); writer.string(partial.boot_session_id);
  }));
  writeMap(writer, sorted(checkpoint.activation_counts), (key) => writer.string(key), (value) => writer.int(value));
  writeMap(writer, sorted(checkpoint.cooldown_marks), (key) => writer.string(key), (mark) => {
    writer.long(mark.active_elapsed_nanos); writer.long(mark.calendar_elapsed_nanos);
  });
  const resources = [...checkpoint.desired_resources.values()].sort((left, right) =>
    resourceSortKey(left.key).localeCompare(resourceSortKey(right.key)));
  writer.int(resources.length);
  for (const { key, desired } of resources) {
    writer.string(key.kind); writer.string(key.id); writer.ulong(desired.generation); writer.nullableString(desired.profile_id);
  }
  writeMap(writer, sorted(checkpoint.timers), (key) => writer.string(key), (timer) => writeTimer(writer, timer));
  writeMap(writer, sorted(checkpoint.timer_generations), (key) => writer.string(key), (value) => writer.ulong(value));
  writeMap(writer, sorted(checkpoint.materialized_timers), (key) => writer.string(key), (summaries) =>
    writeList(writer, summaries, (summary) => {
      writer.string(summary.producer_key); writer.long(summary.selected_utc_millis); writer.boolean(summary.terminal);
    }));
  return PREFIX + base64url(new Uint8Array(writer.bytes));
}

export function decodeAutomationCheckpoint(encoded: string): AutomationCheckpoint {
  if (!encoded.startsWith(PREFIX)) throw new Error('checkpoint_prefix');
  const reader = new Reader(unbase64url(encoded.slice(PREFIX.length)));
  if (reader.int() !== 1) throw new Error('checkpoint_version');
  const desiredResources = new Map<string, { key: ResourceKey; desired: { generation: bigint; profile_id: string | null } }>();
  const checkpoint: AutomationCheckpoint = {
    evaluated_through_sequence: Number(reader.long()), lifecycle: reader.string() as AutomationCheckpoint['lifecycle'],
    study_start_utc_millis: nullableNumber(reader.nullableLong()),
    last_active_elapsed_nanos: reader.long(), last_calendar_elapsed_nanos: reader.long(),
    latch_values: readMap(reader, () => reader.string(), () => reader.boolean()),
    presence_keys: readMap(reader, () => reader.string(), () => new Set(readList(reader, () => reader.string()))),
    held_since_nanos: readMap(reader, () => reader.string(), () => reader.long()),
    prior_condition_values: readMap(reader, () => reader.string(), () => reader.boolean()),
    windows: readMap(reader, () => reader.string(), () => readList(reader, () => ({
      sequence_number: Number(reader.long()), time_nanos: reader.long(), boot_session_id: reader.string(), numeric_value: BigInt(reader.string())
    }))),
    sequences: readMap(reader, () => reader.string(), () => readList(reader, () => ({
      next_step: reader.int(), first_sequence_number: Number(reader.long()), last_sequence_number: Number(reader.long()),
      first_time_nanos: reader.long(), boot_session_id: reader.string()
    }))),
    activation_counts: readMap(reader, () => reader.string(), () => reader.int()),
    cooldown_marks: readMap(reader, () => reader.string(), () => ({
      active_elapsed_nanos: reader.long(), calendar_elapsed_nanos: reader.long()
    })),
    desired_resources: desiredResources,
    timers: new Map(), timer_generations: new Map(), materialized_timers: new Map()
  };
  const resourceCount = reader.int(); if (resourceCount < 0 || resourceCount > 4096) throw new Error('checkpoint_collection');
  for (let index = 0; index < resourceCount; index += 1) {
    const key = { kind: reader.string() as ResourceKey['kind'], id: reader.string() };
    desiredResources.set(resourceKeyString(key), { key, desired: { generation: reader.ulong(), profile_id: reader.nullableString() } });
  }
  checkpoint.timers = readMap(reader, () => reader.string(), () => readTimer(reader));
  checkpoint.timer_generations = readMap(reader, () => reader.string(), () => reader.ulong());
  checkpoint.materialized_timers = readMap(reader, () => reader.string(), () => readList(reader, () => ({
    producer_key: reader.string(), selected_utc_millis: Number(reader.long()), terminal: reader.boolean()
  })));
  if (!reader.done() || encodeAutomationCheckpoint(checkpoint) !== encoded) throw new Error('checkpoint_noncanonical');
  return checkpoint;
}

export function automationCheckpointDigest(checkpoint: AutomationCheckpoint): string {
  const components: string[] = [
    `evaluated=${checkpoint.evaluated_through_sequence}`, `lifecycle=${checkpoint.lifecycle}`,
    `start=${checkpoint.study_start_utc_millis ?? ''}`, `active=${checkpoint.last_active_elapsed_nanos}`,
    `calendar=${checkpoint.last_calendar_elapsed_nanos}`
  ];
  for (const [key, value] of sorted(checkpoint.latch_values)) components.push(`latch:${escape(key)}=${value}`);
  for (const [key, values] of sorted(checkpoint.presence_keys)) for (const value of [...values].sort()) components.push(`presence:${escape(key)}:${escape(value)}`);
  for (const [key, value] of sorted(checkpoint.held_since_nanos)) components.push(`held:${escape(key)}=${value}`);
  for (const [key, value] of sorted(checkpoint.prior_condition_values)) components.push(`prior:${escape(key)}=${value}`);
  for (const [key, values] of sorted(checkpoint.windows)) for (const value of values) components.push(
    `window:${escape(key)}:${value.sequence_number}:${value.time_nanos}:${escape(value.boot_session_id)}:${value.numeric_value}`);
  for (const [key, values] of sorted(checkpoint.sequences)) for (const value of values) components.push(
    `sequence:${escape(key)}:${value.next_step}:${value.first_sequence_number}:${value.last_sequence_number}:${value.first_time_nanos}:${escape(value.boot_session_id)}`);
  for (const [key, value] of sorted(checkpoint.activation_counts)) components.push(`activation:${escape(key)}=${value}`);
  for (const [key, value] of sorted(checkpoint.cooldown_marks)) components.push(`cooldown:${escape(key)}:${value.active_elapsed_nanos}:${value.calendar_elapsed_nanos}`);
  for (const { key, desired } of [...checkpoint.desired_resources.values()].sort((left, right) => resourceSortKey(left.key).localeCompare(resourceSortKey(right.key)))) {
    components.push(`resource:${key.kind}:${escape(key.id)}:${desired.generation}:${escape(desired.profile_id ?? '')}`);
  }
  for (const [, timer] of sorted(checkpoint.timers)) components.push(timerComponent(timer));
  for (const [key, value] of sorted(checkpoint.timer_generations)) components.push(`timer-generation:${escape(key)}:${value}`);
  for (const [key, values] of sorted(checkpoint.materialized_timers)) for (const value of values) components.push(
    `materialized:${escape(key)}:${escape(value.producer_key)}:${value.selected_utc_millis}:${value.terminal}`);
  return deterministicDigest(DOMAIN, components);
}

export function deterministicDigest(domain: string, components: readonly string[]): string {
  const bytes = encoder.encode([domain, ...components].join('\0'));
  return [...sha256(bytes)].map((value) => value.toString(16).padStart(2, '0')).join('');
}

export function actionId(configuration: string, automation: string, intervention: string, trigger: string, causal: string, deadline: string): string {
  return deterministicDigest('particeps-action-v1', [configuration, automation, intervention, trigger, causal, deadline]);
}

export function timerId(configuration: string, automation: string, producer: string): string {
  return deterministicDigest('particeps-timer-v1', [configuration, automation, producer]);
}

function timerComponent(timer: DurableTimer): string {
  let target: string;
  switch (timer.target.type) {
    case 'CALENDAR_UTC': target = `calendar:${timer.target.utc_millis}`; break;
    case 'ACTIVE_ELAPSED': target = `active:${timer.target.elapsed_nanos}`; break;
    case 'SAME_BOOT_MONOTONIC': target = `monotonic:${escape(timer.target.boot_session_id)}:${timer.target.elapsed_realtime_nanos}`; break;
  }
  return `timer:${timer.id}:${escape(timer.automation_id)}:${timer.generation}:${timer.causal_sequence}:${escape(timer.producer_key)}:${target}:${timer.logical_deadline_utc_millis ?? ''}:${timer.expires_at_utc_millis ?? ''}`;
}

function escape(value: string): string { return value.replaceAll('%', '%25').replaceAll('\0', '%00').replaceAll(':', '%3a').replaceAll('=', '%3d'); }

function base64url(bytes: Uint8Array): string {
  let binary = ''; for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}

function unbase64url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]*$/.test(value) || value.length % 4 === 1) throw new Error('checkpoint_base64url');
  const binary = atob(value.replaceAll('-', '+').replaceAll('_', '/') + '='.repeat((4 - value.length % 4) % 4));
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}
