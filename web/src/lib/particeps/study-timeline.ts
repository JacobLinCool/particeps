import { firstInstant, localDate, nextDate, produceStandardScheduleTimer, studyLocalWindow } from './automation/timers';
import type { MaterializedTimerSummary, TimerProductionRequest } from './automation/types';
import { trafficShapingEnabled } from './types';
import type { AutomationDefinition, CollectorId, OccurrenceAutomation, ResourceBindingAutomation, StateCondition, StudyConfiguration } from './types';
import { describeRule, profileDescription, resourceName } from './study-timeline-labels';

export type TimelineLocale = 'en' | 'zh-TW';
export interface StudyTimelineOptions {
  /** Day 1 is the local calendar date on which the participant starts. */
  studyDay?: number;
  /** A reference scenario; these fields do not change the signed configuration. */
  startDate?: string;
  startTime?: string;
  timeZone?: string;
  locale?: TimelineLocale;
}
export interface TimelineSegment {
  startMinute: number;
  endMinute: number;
  startTime: string;
  endTime: string;
  state: 'active' | 'inactive' | 'conditional' | 'outside-study';
  /** Multiple possibilities retain signed case priority; null means stopped. */
  profileIds: (string | null)[];
  label: string;
  detail: string;
}
export interface TimelineLane {
  id: string;
  kind: 'collector' | 'actuator';
  resourceId: string;
  label: string;
  detail: string;
  segments: TimelineSegment[];
  editPath: string;
}
export interface TimelineEvent {
  id: string;
  automationId: string;
  interventionId: string;
  kind: 'survey' | 'notification';
  title: string;
  questionCount: number;
  atMinute: number;
  localTime: string;
  instant: string;
  availabilityEndsAt: string;
  certainty: 'scheduled' | 'conditional';
  detail: string;
  editPath: string;
}
export interface TimelineRandomWindow {
  id: string;
  automationId: string;
  interventionId: string;
  title: string;
  questionCount: number;
  startMinute: number;
  endMinute: number;
  startTime: string;
  endTime: string;
  detail: string;
  editPath: string;
}
export interface TimelineRule {
  id: string;
  type: AutomationDefinition['type'];
  summary: string;
  conditional: boolean;
  editPath: string;
}
export interface StudyTimelineSummary {
  reference: Required<StudyTimelineOptions>;
  day: { number: number; totalDays: number; date: string; durationMinutes: number; activeStartMinute: number; activeEndMinute: number; ticks: { minute: number; label: string }[] };
  studyStart: string;
  studyEnd: string;
  lanes: TimelineLane[];
  events: TimelineEvent[];
  randomWindows: TimelineRandomWindow[];
  rules: TimelineRule[];
  assumptions: string[];
  warnings: string[];
}

type Truth = boolean | 'unknown';
interface ProjectionContext {
  start: number;
  end: number;
  dayStart: number;
  dayEnd: number;
  timeZone: string;
  locale: TimelineLocale;
  warnings: Set<string>;
}
const MINUTE = 60_000;
const DAY = 86_400_000;
const text = (locale: TimelineLocale, en: string, zh: string): string => locale === 'zh-TW' ? zh : en;

/**
 * Bounded static projection of a reference participant with uninterrupted running time.
 * It uses the runtime's civil-time resolution and standard schedule producer. It does not
 * simulate observations, latch/presence history, random entropy, or delivery success.
 * Unknown conditions use three-valued logic, retaining every reachable ordered profile.
 */
export function summarizeStudyTimeline(configuration: StudyConfiguration, options: StudyTimelineOptions = {}): StudyTimelineSummary {
  const reference: Required<StudyTimelineOptions> = {
    studyDay: options.studyDay ?? 1, startDate: options.startDate ?? '2026-01-01',
    startTime: options.startTime ?? '00:00', timeZone: options.timeZone ?? 'UTC', locale: options.locale ?? 'en'
  };
  const { locale, timeZone, startDate, startTime, studyDay } = reference;
  if (!/^\d{4}-\d{2}-\d{2}$/.test(startDate) || !Number.isFinite(Date.parse(`${startDate}T00:00:00Z`)) ||
    new Date(`${startDate}T00:00:00Z`).toISOString().slice(0, 10) !== startDate) {
    throw new Error(text(locale, 'Choose a valid reference start date.', '請選擇有效的參考開始日期。'));
  }
  if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(startTime)) {
    throw new Error(text(locale, 'Choose a reference start time from 00:00 to 23:59.', '請選擇 00:00 至 23:59 的參考開始時間。'));
  }
  try { new Intl.DateTimeFormat('en', { timeZone }).format(0); } catch {
    throw new Error(text(locale, 'Enter a valid IANA time zone, such as Asia/Taipei.', '請輸入有效的 IANA 時區，例如 Asia/Taipei。'));
  }
  if (!Number.isInteger(configuration.duration_hours) || configuration.duration_hours < 1 || configuration.duration_hours > 8760) {
    throw new Error(text(locale, 'Set the study duration to 1–8,760 whole hours to view its timeline.', '請將研究期限設為 1 至 8,760 個完整小時，才能查看時間軸。'));
  }
  const start = firstInstant(startDate, startTime, timeZone);
  if (start === null) throw new Error(text(locale, 'This local start time does not exist because the clock moves forward. Choose another time.', '此當地開始時間因時鐘向前調整而不存在，請選擇其他時間。'));
  const end = start + configuration.duration_hours * 3_600_000;
  const totalDays = Math.round((Date.parse(`${localDate(end - 1, timeZone)}T00:00:00Z`) - Date.parse(`${startDate}T00:00:00Z`)) / DAY) + 1;
  if (!Number.isInteger(studyDay) || studyDay < 1 || studyDay > totalDays) {
    throw new Error(text(locale, `Choose a study day from 1 to ${totalDays}.`, `請選擇第 1 至 ${totalDays} 天。`));
  }
  const date = addDays(startDate, studyDay - 1);
  const dayStart = firstMinuteOfDate(date, timeZone);
  const dayEnd = firstMinuteOfDate(nextDate(date), timeZone);
  if (dayStart === null || dayEnd === null) throw new Error(text(locale, 'This local date is skipped by a time zone change. Choose another reference date.', '此當地日期因時區變動而被跳過，請選擇其他參考日期。'));
  const context: ProjectionContext = { start, end, dayStart, dayEnd, timeZone, locale, warnings: new Set() };
  const durationMinutes = (dayEnd - dayStart) / MINUTE;
  const bindings = configuration.automations.filter((automation) => automation.type === 'resource_binding');
  const lanes: TimelineLane[] = configuration.collectors.map((collector, index) => {
    const matching = bindings.filter((binding) => binding.resource.kind === 'collector' && binding.resource.id === collector.id);
    return resourceLane(configuration, matching, 'collector', collector.id, `collectors.${index}`, context);
  });
  if (trafficShapingEnabled(configuration.traffic_shaping)) {
    lanes.push(resourceLane(configuration, bindings.filter((binding) => binding.resource.kind === 'actuator' && binding.resource.id === 'traffic-shaping.v1'),
      'actuator', 'traffic-shaping.v1', 'traffic_shaping', context));
  }
  const events: TimelineEvent[] = [];
  const randomWindows: TimelineRandomWindow[] = [];
  configuration.automations.forEach((automation, index) => {
    if (automation.type !== 'occurrence') return;
    const editPath = `automations.${index}`;
    if (automation.trigger.type === 'schedule' && automation.trigger.schedule.type === 'random_window') {
      const schedule = automation.trigger.schedule;
      schedule.local_windows.forEach((window, windowIndex) => {
        const from = firstInstant(date, window.start_local_time, timeZone);
        const to = firstInstant(date, window.end_local_time, timeZone);
        if (from === null || to === null || to <= from) return;
        const left = Math.max(start, from), right = Math.min(end, to);
        if (right <= left) return;
        // Keep all possible guarded subwindows, without choosing any participant's random time.
        const boundaries = conditionBoundaries(automation.guard, left, right, context);
        for (let part = 0; part < boundaries.length - 1; part += 1) {
          const low = boundaries[part], high = boundaries[part + 1];
          if (evaluate(automation.guard, low, context) === false) continue;
          const action = interventionInfo(configuration, automation, locale);
          randomWindows.push({ id: `${automation.id}:${windowIndex}:${part}`, automationId: automation.id,
            interventionId: automation.intervention_id, title: action.title, questionCount: action.questionCount,
            startMinute: (low - dayStart) / MINUTE, endMinute: (high - dayStart) / MINUTE,
            startTime: clockLabel(low, context), endTime: clockLabel(high, context), editPath,
            detail: describeRule(configuration, automation, locale) });
        }
      });
      return;
    }
    const candidates = occurrenceCandidates(automation, context);
    let accepted = 0, lastAccepted: number | null = null, uncertainHistory = false;
    for (const instant of candidates) {
      if (instant < start || instant >= end) continue;
      const guard = evaluate(automation.guard, instant, context);
      if (guard === false) continue;
      if (accepted >= automation.maximum_activations) break;
      if (lastAccepted !== null && automation.cooldown && instant - lastAccepted < automation.cooldown.duration_seconds * 1000) continue;
      const conditional = guard === 'unknown' || uncertainHistory;
      if (conditional) uncertainHistory = true;
      else { accepted += 1; lastAccepted = instant; }
      if (instant < dayStart || instant >= dayEnd) continue;
      const action = interventionInfo(configuration, automation, locale);
      events.push({ id: `${automation.id}:${instant}`, automationId: automation.id, interventionId: automation.intervention_id,
        kind: action.kind, title: action.title, questionCount: action.questionCount,
        atMinute: (instant - dayStart) / MINUTE, localTime: clockLabel(instant, context), instant: new Date(instant).toISOString(),
        availabilityEndsAt: new Date(Math.min(end, instant + automation.availability_seconds * 1000)).toISOString(),
        certainty: conditional ? 'conditional' : 'scheduled', detail: describeRule(configuration, automation, locale), editPath });
    }
  });
  const ticks = Array.from({ length: Math.ceil(durationMinutes / 180) }, (_, index) => ({
    minute: index * 180, label: clockLabel(dayStart + index * 180 * MINUTE, context)
  }));
  ticks.push({ minute: durationMinutes, label: '24:00' });
  const assumptions = [
    text(locale, 'Reference scenario only: change the start date, time and time zone to inspect a possible participant journey. These controls do not change the study.', '這是參考情境：調整開始日期、時間與時區，即可查看可能的參與流程。這些控制不會修改研究設定。'),
    text(locale, 'Day 1 is the local calendar date of activation; it may be partial. The study ends after the configured number of calendar hours, even during a pause.', '第 1 天是啟動時的當地日期，可能只有部分時段。研究在設定的日曆小時數後結束，暫停期間也計入期限。'),
    text(locale, 'This projection assumes uninterrupted running, immediate evaluation and a constant device time zone. Actual permissions, battery restrictions, pauses and delivery delays can change what is collected or delivered.', '此圖假設研究持續執行、規則即時評估，且裝置時區不變。實際權限、省電限制、暫停及傳送延遲，都可能影響收集或通知。'),
    text(locale, 'Active running time stops during pauses; calendar time keeps advancing. All resources stop during a pause. Resuming may create a new rising edge; event history is reset.', '執行時間在暫停時停止累計；日曆時間持續前進。暫停時所有資源停止，恢復後可能產生新的條件上升沿，事件歷史也會重設。'),
    text(locale, 'Local times use the device time zone. The runtime skips nonexistent local times and selects the first occurrence of a repeated local time.', '當地時間依裝置時區計算。執行時會跳過不存在的當地時間，並在重複時間中選擇第一次出現的時刻。'),
    text(locale, 'Random bands show possible windows only. Counts, minimum separation, prior selections, guards and activation limits determine eligibility on the device; no random instants are chosen here.', '隨機色帶僅顯示可能時段。次數、最小間隔、先前抽選、守衛條件及啟動上限，會在裝置上共同決定資格；此處不抽選時間。')
  ];
  if (durationMinutes !== 1440) context.warnings.add(text(locale,
    `The clock changes on this date: this local day spans ${durationMinutes / 60} elapsed hours. Tick labels follow the local clock; band width follows elapsed time.`,
    `此日時鐘有調整，當地一天實際經過 ${durationMinutes / 60} 小時。刻度依當地時鐘標示，色帶寬度依實際經過時間計算。`));
  const rules = configuration.automations.map((automation, index): TimelineRule => ({
    id: automation.id, type: automation.type, summary: describeRule(configuration, automation, locale),
    conditional: automation.type === 'resource_binding' ? automation.cases.some((entry) => isHistoryDependent(entry.condition))
      : automation.trigger.type !== 'schedule' && (automation.trigger.type !== 'condition_rising_edge' || isHistoryDependent(automation.trigger.condition)) || isHistoryDependent(automation.guard),
    editPath: `automations.${index}`
  }));
  return { reference, day: { number: studyDay, totalDays, date, durationMinutes, ticks,
    activeStartMinute: Math.max(0, (start - dayStart) / MINUTE), activeEndMinute: Math.min(durationMinutes, (end - dayStart) / MINUTE) },
    studyStart: new Date(start).toISOString(), studyEnd: new Date(end).toISOString(), lanes,
    events: events.sort((a, b) => a.atMinute - b.atMinute || a.automationId.localeCompare(b.automationId)),
    randomWindows, rules, assumptions, warnings: [...context.warnings] };
}

function resourceLane(configuration: StudyConfiguration, bindings: ResourceBindingAutomation[], kind: TimelineLane['kind'], id: string, editPath: string, context: ProjectionContext): TimelineLane {
  const binding = bindings.length === 1 ? bindings[0] : undefined;
  const { locale, dayStart, dayEnd, start, end } = context;
  if (bindings.length !== 1) context.warnings.add(text(locale, `${id} needs exactly one resource binding before its activity can be projected.`, `${id} 必須有且僅有一條資源綁定規則，才能推算啟用時段。`));
  const boundaries = [...new Set([dayStart, dayEnd, Math.max(dayStart, start), Math.min(dayEnd, end),
    ...(binding?.cases.flatMap((entry) => conditionBoundaries(entry.condition, dayStart, dayEnd, context)) ?? [])])]
    .filter((instant) => instant >= dayStart && instant <= dayEnd).sort((a, b) => a - b);
  const segments: TimelineSegment[] = [];
  for (let index = 0; index < boundaries.length - 1; index += 1) {
    const left = boundaries[index], right = boundaries[index + 1];
    const outside = left < start || left >= end;
    const profiles = outside ? [null] : binding ? possibleProfiles(binding, left, context) : [];
    const state = outside ? 'outside-study' : profiles.length !== 1 ? 'conditional' : profiles[0] === null ? 'inactive' : 'active';
    const label = outside ? text(locale, 'Outside study', '研究時段外') : state === 'conditional' ? text(locale, 'Conditional', '依條件決定')
      : profiles[0] === null ? text(locale, 'Stopped', '停止') : profiles[0];
    const detail = outside ? text(locale, 'The participant has not started or the study has ended.', '參與者尚未開始，或研究已結束。')
      : profiles.length ? profiles.map((profile) => profileDescription(configuration, kind, id, profile, locale)).join(text(locale, ' OR ', ' 或 '))
        : text(locale, 'Resolve the resource binding to preview this row.', '請先修正資源綁定，才能預覽此列。');
    const previous = segments.at(-1);
    if (previous && previous.state === state && previous.detail === detail && JSON.stringify(previous.profileIds) === JSON.stringify(profiles)) {
      previous.endMinute = (right - dayStart) / MINUTE; previous.endTime = clockLabel(right, context);
    } else segments.push({ startMinute: (left - dayStart) / MINUTE, endMinute: (right - dayStart) / MINUTE,
      startTime: clockLabel(left, context), endTime: clockLabel(right, context), state, profileIds: profiles, label, detail });
  }
  const collector = configuration.collectors.find((item) => item.id === id);
  const detail = kind === 'collector'
    ? text(locale, collector?.required ? 'Required data source' : 'Optional data source', collector?.required ? '必要資料來源' : '選用資料來源')
    : trafficShapingEnabled(configuration.traffic_shaping)
      ? configuration.traffic_shaping.target_packages === 'all' ? text(locale, 'All apps', '所有 App') : configuration.traffic_shaping.target_packages.join(', ')
      : '';
  return { id: `${kind}:${id}`, kind, resourceId: id, label: resourceName(id as CollectorId, locale), detail, segments, editPath };
}

function possibleProfiles(binding: ResourceBindingAutomation, instant: number, context: ProjectionContext): (string | null)[] {
  const candidates: (string | null)[] = [];
  for (const entry of binding.cases) {
    const value = evaluate(entry.condition, instant, context);
    if (value !== false) candidates.push(entry.profile_id);
    if (value === true) return [...new Set(candidates)];
  }
  candidates.push(binding.default_profile_id);
  return [...new Set(candidates)];
}

function evaluate(condition: StateCondition | null, instant: number, context: ProjectionContext): Truth {
  if (!condition) return true;
  switch (condition.type) {
    case 'study_session_active': return instant >= context.start && instant < context.end;
    case 'study_local_window':
      if (condition.start_local_time >= condition.end_local_time) {
        context.warnings.add(text(context.locale, 'A local window ends before it starts. Local windows must stay within one day; split an overnight period across two days.', '有當地時段的結束時間未晚於開始時間。每個時段必須在同一天內；跨午夜時段請拆成兩段。'));
        return 'unknown';
      }
      return studyLocalWindow(condition, context.start, instant, context.timeZone).active;
    case 'elapsed_at_least': return instant - context.start >= condition.duration_seconds * 1000;
    case 'all': {
      const values = condition.conditions.map((child) => evaluate(child, instant, context));
      return values.includes(false) ? false : values.includes('unknown') ? 'unknown' : true;
    }
    case 'any': {
      const values = condition.conditions.map((child) => evaluate(child, instant, context));
      return values.includes(true) ? true : values.includes('unknown') ? 'unknown' : false;
    }
    case 'not': { const value = evaluate(condition.condition, instant, context); return value === 'unknown' ? 'unknown' : !value; }
    case 'event_latch': case 'keyed_presence': case 'window_threshold': case 'held_for': return 'unknown';
  }
}

function conditionBoundaries(condition: StateCondition | null, from: number, to: number, context: ProjectionContext): number[] {
  const values = [from, to];
  const collect = (entry: StateCondition | null): void => {
    if (!entry) return;
    switch (entry.type) {
      case 'all': case 'any': entry.conditions.forEach(collect); break;
      case 'not': collect(entry.condition); break;
      case 'elapsed_at_least': values.push(context.start + entry.duration_seconds * 1000); break;
      case 'study_local_window': {
        let cursor = Math.max(from, context.start);
        for (let count = 0; count < 740 && cursor < to; count += 1) {
          const window = studyLocalWindow(entry, context.start, cursor, context.timeZone);
          if (window.nextBoundary === null || window.nextBoundary >= to) break;
          values.push(window.nextBoundary);
          cursor = window.nextBoundary;
        }
        break;
      }
    }
  };
  collect(condition);
  return [...new Set(values)].filter((value) => value >= from && value <= to).sort((a, b) => a - b);
}

function occurrenceCandidates(automation: OccurrenceAutomation, context: ProjectionContext): number[] {
  const trigger = automation.trigger;
  const through = Math.min(context.end, context.dayEnd);
  if (trigger.type === 'condition_rising_edge') {
    if (isHistoryDependent(trigger.condition)) return [];
    return conditionBoundaries(trigger.condition, context.start, through, context).filter((instant) =>
      instant < through && evaluate(trigger.condition, instant, context) === true &&
      (instant === context.start || evaluate(trigger.condition, instant - 1, context) === false));
  }
  if (trigger.type !== 'schedule' || trigger.schedule.type === 'random_window') return [];
  const result: number[] = [];
  const materialized: MaterializedTimerSummary[] = [];
  const request: TimerProductionRequest = {
    configuration_sha256: '0'.repeat(64), automation, schedule: trigger.schedule,
    clock: { now: { wall_time_utc_millis: context.start, elapsed_realtime_nanos: 0n, boot_session_id: 'timeline-reference' },
      active_elapsed_nanos: 0n, calendar_elapsed_nanos: 0n, zone_id: context.timeZone },
    study_start_utc_millis: context.start, study_deadline_utc_millis: context.end, causal_sequence: 1,
    current_generation: 0n, session_state: 'RUNNING', pending_timer: null, materialized
  };
  // Protocol bounds are 512 interval ordinals and <= 367 local dates for a one-year study.
  for (let count = 0; count < 740; count += 1) {
    const timer = produceStandardScheduleTimer(request);
    if (!timer) break;
    const instant = timer.target.type === 'CALENDAR_UTC' ? timer.target.utc_millis
      : timer.target.type === 'ACTIVE_ELAPSED' ? context.start + Number(timer.target.elapsed_nanos / 1_000_000n) : null;
    if (instant === null || instant >= through) break;
    result.push(instant);
    materialized.push({ producer_key: timer.producer_key, selected_utc_millis: instant, terminal: true });
    request.current_generation = timer.generation;
    // Advancing the reference wall clock makes daily lookup linear in the study's days.
    request.clock.now.wall_time_utc_millis = instant;
  }
  return result;
}

function isHistoryDependent(condition: StateCondition | null): boolean {
  if (!condition) return false;
  switch (condition.type) {
    case 'event_latch': case 'keyed_presence': case 'window_threshold': case 'held_for': return true;
    case 'all': case 'any': return condition.conditions.some(isHistoryDependent);
    case 'not': return isHistoryDependent(condition.condition);
    default: return false;
  }
}
function interventionInfo(configuration: StudyConfiguration, automation: OccurrenceAutomation, locale: TimelineLocale) {
  const action = configuration.interventions.find((item) => item.id === automation.intervention_id)?.action;
  const survey = action?.type === 'survey' ? configuration.surveys.find((item) => item.id === action.survey_id) : undefined;
  return { kind: action?.type ?? 'notification', title: survey ? survey.title.translations[locale] || survey.title.default : action?.notification_title || automation.intervention_id,
    questionCount: survey?.questions.length ?? 0 };
}
function addDays(date: string, count: number): string { return new Date(Date.parse(`${date}T00:00:00Z`) + count * DAY).toISOString().slice(0, 10); }
function firstMinuteOfDate(date: string, timeZone: string): number | null {
  for (let minute = 0; minute < 1440; minute += 1) {
    const instant = firstInstant(date, `${Math.floor(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}`, timeZone);
    if (instant !== null) return instant;
  }
  return null;
}
function clockLabel(instant: number, context: ProjectionContext): string {
  if (instant === context.dayEnd) return '24:00';
  return new Intl.DateTimeFormat('en-GB', { timeZone: context.timeZone, hourCycle: 'h23', hour: '2-digit', minute: '2-digit',
    ...(instant % MINUTE ? { second: '2-digit' as const } : {}) }).format(instant);
}
