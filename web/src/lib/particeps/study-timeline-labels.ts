import { en } from '../i18n/en';
import { zhTW } from '../i18n/zh-TW';
import { isCollectorId, trafficShapingEnabled } from './types';
import type { AutomationDefinition, DurationClock, EventMatcher, StateCondition, StudyConfiguration } from './types';
import type { TimelineLocale } from './study-timeline';

const t = (locale: TimelineLocale, english: string, chinese: string): string => locale === 'zh-TW' ? chinese : english;
const clock = (value: DurationClock, locale: TimelineLocale): string => value === 'ACTIVE_RUNNING_TIME'
  ? t(locale, 'active running time', '執行時間') : t(locale, 'calendar time', '日曆時間');
const seconds = (value: number, locale: TimelineLocale): string => value % 3600 === 0
  ? t(locale, `${value / 3600} h`, `${value / 3600} 小時`) : value % 60 === 0
    ? t(locale, `${value / 60} min`, `${value / 60} 分鐘`) : t(locale, `${value} s`, `${value} 秒`);
const matcher = (value: EventMatcher): string => `${value.event.source_id} / ${value.event.event_type}${value.predicates.length
  ? ` (${value.predicates.map((predicate) => `${predicate.field} ${predicate.operator} ${predicate.operator === 'in' ? predicate.values.join(', ') : predicate.value}`).join('; ')})` : ''}`;

export function resourceName(id: string, locale: TimelineLocale): string {
  if (id === 'traffic-shaping.v1') return t(locale, 'Network speed', '網路速度');
  return isCollectorId(id) ? (locale === 'zh-TW' ? zhTW : en).collector[id].name : id;
}

export function describeCondition(condition: StateCondition, locale: TimelineLocale): string {
  switch (condition.type) {
    case 'study_session_active': return t(locale, 'while the study is active', '研究啟用期間');
    case 'study_local_window': return t(locale,
      `local days ${condition.first_day}–${condition.last_day}, ${condition.start_local_time}–${condition.end_local_time} (end excluded)`,
      `當地第 ${condition.first_day}–${condition.last_day} 天，${condition.start_local_time}–${condition.end_local_time}（不含結束時刻）`);
    case 'elapsed_at_least': return t(locale,
      `after ${seconds(condition.duration_seconds, locale)} of ${clock(condition.clock, locale)}`,
      `${clock(condition.clock, locale)}累計達 ${seconds(condition.duration_seconds, locale)}`);
    case 'held_for': return t(locale,
      `(${describeCondition(condition.condition, locale)}) holds for ${seconds(condition.duration_seconds, locale)} of ${clock(condition.clock, locale)}; depends on continuous condition history`,
      `（${describeCondition(condition.condition, locale)}）持續 ${seconds(condition.duration_seconds, locale)}，依${clock(condition.clock, locale)}計算；須依連續條件歷史判定`);
    case 'event_latch': return t(locale,
      `after [${condition.set_when.map(matcher).join(' OR ')}], until [${condition.reset_when.map(matcher).join(' OR ')}]`,
      `[${condition.set_when.map(matcher).join(' 或 ')}] 發生後啟用，直到 [${condition.reset_when.map(matcher).join(' 或 ')}] 發生`);
    case 'keyed_presence': return t(locale,
      `while a ${condition.key_field} is present: enter [${condition.enter_when.map(matcher).join(' OR ')}], exit [${condition.exit_when.map(matcher).join(' OR ')}]`,
      `${condition.key_field} 存在期間：進入 [${condition.enter_when.map(matcher).join(' 或 ')}]；離開 [${condition.exit_when.map(matcher).join(' 或 ')}]`);
    case 'window_threshold': return t(locale,
      `${condition.aggregate.type === 'sum' ? `sum of ${condition.aggregate.field}` : 'event count'} over ${seconds(condition.window_seconds, locale)} ${condition.comparison.operator} ${condition.comparison.value}, matching ${matcher(condition.selector)}; ${condition.evaluation_clock}`,
      `${seconds(condition.window_seconds, locale)} 內的${condition.aggregate.type === 'sum' ? `${condition.aggregate.field} 總和` : '事件數'} ${condition.comparison.operator} ${condition.comparison.value}，事件為 ${matcher(condition.selector)}；${condition.evaluation_clock}`);
    case 'all': return `(${condition.conditions.map((child) => describeCondition(child, locale)).join(t(locale, ') AND (', '）且（'))})`;
    case 'any': return `(${condition.conditions.map((child) => describeCondition(child, locale)).join(t(locale, ') OR (', '）或（'))})`;
    case 'not': return t(locale, `NOT (${describeCondition(condition.condition, locale)})`, `不符合（${describeCondition(condition.condition, locale)}）`);
  }
}

export function profileDescription(configuration: StudyConfiguration, kind: 'collector' | 'actuator', resourceId: string, profileId: string | null, locale: TimelineLocale): string {
  if (profileId === null) return t(locale, 'Stopped', '停止');
  if (kind === 'actuator' && trafficShapingEnabled(configuration.traffic_shaping)) {
    const profile = configuration.traffic_shaping.profiles.find((item) => item.id === profileId);
    if (!profile) return t(locale, `${profileId}: profile missing`, `${profileId}：找不到此設定檔`);
    return `${profileId} · ${t(locale, 'upload', '上傳')} ${profile.uplink_kbps === null ? t(locale, 'unlimited', '不限速') : `${profile.uplink_kbps} kbps`}, ${t(locale, 'download', '下載')} ${profile.downlink_kbps === null ? t(locale, 'unlimited', '不限速') : `${profile.downlink_kbps} kbps`}`;
  }
  const profile = configuration.collectors.find((item) => item.id === resourceId)?.profiles.find((item) => item.id === profileId);
  if (!profile) return t(locale, `${profileId}: profile missing`, `${profileId}：找不到此設定檔`);
  const details = Object.entries(profile.config).map(([key, value]) => {
    switch (key) {
      case 'sampling_period_us': return t(locale, `${1_000_000 / Number(value)} samples/s`, `每秒 ${1_000_000 / Number(value)} 筆`);
      case 'poll_interval_seconds': return t(locale, `every ${value} s`, `每 ${value} 秒`);
      case 'trajectory_sampling_hz': return t(locale, `${value} trajectory samples/s`, `每秒 ${value} 筆軌跡`);
      case 'maximum_report_latency_us': return t(locale, `batch delay ≤ ${Number(value) / 1000} ms`, `批次延遲 ≤ ${Number(value) / 1000} 毫秒`);
      case 'interval_millis': return t(locale, `interval ${Number(value) / 1000} s`, `間隔 ${Number(value) / 1000} 秒`);
      case 'minimum_interval_millis': return t(locale, `minimum interval ${Number(value) / 1000} s`, `最短間隔 ${Number(value) / 1000} 秒`);
      case 'maximum_batch_delay_millis': return t(locale, `batch delay ≤ ${Number(value) / 1000} s`, `批次延遲 ≤ ${Number(value) / 1000} 秒`);
      case 'minimum_displacement_millimeters': return t(locale, `displacement ≥ ${Number(value) / 1000} m`, `位移 ≥ ${Number(value) / 1000} 公尺`);
      case 'include_bandwidth_estimates': return t(locale, value ? 'includes bandwidth estimates' : 'without bandwidth estimates', value ? '包含頻寬估計' : '不含頻寬估計');
      case 'transports': return t(locale, 'transports: ', '網路類型：') + (value as string[]).join(', ');
      default: return `${key}: ${String(value)}`;
    }
  });
  return `${profileId} · ${details.length ? details.join(', ') : t(locale, 'event-driven', '依事件產生資料')}`;
}

export function describeRule(configuration: StudyConfiguration, automation: AutomationDefinition, locale: TimelineLocale): string {
  if (automation.type === 'resource_binding') {
    return [resourceName(automation.resource.id, locale), t(locale, 'First matching case wins.', '依順序採用第一個符合的條件。'),
      ...automation.cases.map((entry, index) => `${index + 1}. ${describeCondition(entry.condition, locale)} → ${profileDescription(configuration, automation.resource.kind, automation.resource.id, entry.profile_id, locale)}`),
      t(locale, 'Otherwise: ', '其他情況：') + profileDescription(configuration, automation.resource.kind, automation.resource.id, automation.default_profile_id, locale)].join('\n');
  }
  const action = configuration.interventions.find((item) => item.id === automation.intervention_id)?.action;
  const survey = action?.type === 'survey' ? configuration.surveys.find((item) => item.id === action.survey_id) : undefined;
  const actionDescription = survey ? t(locale,
    `Survey “${survey.title.translations[locale] || survey.title.default}” · ${survey.questions.length} questions`,
    `問卷「${survey.title.translations[locale] || survey.title.default}」· ${survey.questions.length} 題`)
    : t(locale, `Notification “${action?.notification_title || automation.intervention_id}”`, `通知「${action?.notification_title || automation.intervention_id}」`);
  const trigger = automation.trigger;
  let when: string;
  if (trigger.type === 'schedule') {
    const schedule = trigger.schedule;
    switch (schedule.type) {
      case 'one_time': when = t(locale, `Once after ${schedule.offset_minutes} min of ${clock(schedule.clock, locale)}`, `${clock(schedule.clock, locale)}累計 ${schedule.offset_minutes} 分鐘時發送一次`); break;
      case 'interval': when = t(locale, `Every ${schedule.interval_minutes} min, starting at ${schedule.start_offset_minutes} min of ${clock(schedule.clock, locale)}`, `從${clock(schedule.clock, locale)}第 ${schedule.start_offset_minutes} 分鐘開始，每 ${schedule.interval_minutes} 分鐘一次`); break;
      case 'daily_local': when = t(locale, `Each local date at ${schedule.local_time}`, `每個當地日期 ${schedule.local_time}`); break;
      case 'random_window': when = t(locale,
        `Random within ${schedule.local_windows.map((window) => `${window.start_local_time}–${window.end_local_time}`).join(', ')}; ${schedule.occurrences_per_window} per window, ≤ ${schedule.maximum_occurrences_per_day}/day, ≤ ${schedule.maximum_occurrences_total} total, ≥ ${schedule.minimum_separation_minutes} min apart. Bands show possibilities, not guaranteed occurrences.`,
        `於 ${schedule.local_windows.map((window) => `${window.start_local_time}–${window.end_local_time}`).join('、')} 隨機抽選；每時段 ${schedule.occurrences_per_window} 次，每天最多 ${schedule.maximum_occurrences_per_day} 次，總計最多 ${schedule.maximum_occurrences_total} 次，間隔至少 ${schedule.minimum_separation_minutes} 分鐘。色帶表示可能時段，不保證發送次數。`); break;
    }
  } else if (trigger.type === 'condition_rising_edge') when = t(locale, `When this condition becomes true: ${describeCondition(trigger.condition, locale)}`, `以下條件由不符合轉為符合時：${describeCondition(trigger.condition, locale)}`);
  else if (trigger.type === 'event_match') when = t(locale, `On ${matcher(trigger.selector)}; ${trigger.evaluation_clock}`, `當 ${matcher(trigger.selector)} 發生；${trigger.evaluation_clock}`);
  else if (trigger.type === 'sequence') when = t(locale,
    `Sequence ${trigger.steps.map(matcher).join(' → ')} within ${seconds(trigger.within_seconds, locale)}; ${trigger.evaluation_clock}`,
    `${seconds(trigger.within_seconds, locale)} 內依序發生 ${trigger.steps.map(matcher).join(' → ')}；${trigger.evaluation_clock}`);
  else when = describeCondition(trigger, locale);
  return [actionDescription, when,
    automation.guard ? t(locale, `Only if ${describeCondition(automation.guard, locale)}`, `且須符合：${describeCondition(automation.guard, locale)}`) : t(locale, 'No additional guard.', '無其他守衛條件。'),
    t(locale, `Available for ${seconds(automation.availability_seconds, locale)}, limited by the study end. Maximum ${automation.maximum_activations} activations across the study.`, `有效 ${seconds(automation.availability_seconds, locale)}，最晚至研究結束。整個研究最多啟動 ${automation.maximum_activations} 次。`),
    automation.cooldown ? t(locale, `Cooldown: ${seconds(automation.cooldown.duration_seconds, locale)} of ${clock(automation.cooldown.clock, locale)}.`, `冷卻時間：${seconds(automation.cooldown.duration_seconds, locale)}，依${clock(automation.cooldown.clock, locale)}計算。`) : t(locale, 'No cooldown.', '無冷卻時間。')].join('\n');
}
