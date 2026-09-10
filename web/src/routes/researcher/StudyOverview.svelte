<script lang="ts">
  import Button from '$lib/ui/Button.svelte';
  import Icon from '$lib/ui/Icon.svelte';
  import { trafficShapingEnabled, type StudyConfiguration } from '$lib/particeps/types';
  import { summarizeStudyTimeline, type StudyTimelineSummary, type TimelineLocale } from '$lib/particeps/study-timeline';

  interface Props { configuration: StudyConfiguration; locale?: TimelineLocale; onedit?: (path: string) => void }
  let { configuration, locale = 'en', onedit }: Props = $props();
  const uid = $props.id();
  let studyDay = $state(1);
  let startDate = $state('2026-01-01');
  let startTime = $state('00:00');
  let timeZone = $state('UTC');
  let selection = $state('');
  const t = (english: string, chinese: string) => locale === 'zh-TW' ? chinese : english;
  const projection = $derived.by((): { summary: StudyTimelineSummary | null; error: string } => {
    try { return { summary: summarizeStudyTimeline(configuration, { studyDay, startDate, startTime, timeZone, locale }), error: '' }; }
    catch (error) { return { summary: null, error: error instanceof Error ? error.message : t('Check the reference time settings.', '請檢查參考時間設定。') }; }
  });
  const summary = $derived(projection.summary);
  const eventGroups = $derived.by(() => {
    if (!summary) return [];
    const ids = [...new Set([...summary.events, ...summary.randomWindows].map((event) => event.interventionId))];
    return ids.map((id) => {
      const events = summary.events.filter((event) => event.interventionId === id);
      const windows = summary.randomWindows.filter((window) => window.interventionId === id);
      const first = events[0] ?? windows[0];
      return { id, title: first.title, questionCount: first.questionCount, events, windows };
    });
  });
  const details = $derived.by(() => {
    if (!summary || !selection) return null;
    for (const lane of summary.lanes) {
      for (const [index, segment] of lane.segments.entries()) {
        if (selection === `${lane.id}:${index}`) return { title: `${lane.label} · ${segment.startTime}–${segment.endTime}`,
          text: `${segment.label}\n${segment.detail}`, editPath: lane.editPath };
      }
    }
    const event = summary.events.find((item) => item.id === selection);
    if (event) return { title: `${event.localTime} · ${event.title}`, text: event.detail, editPath: event.editPath };
    const window = summary.randomWindows.find((item) => item.id === selection);
    return window ? { title: `${window.startTime}–${window.endTime} · ${window.title}`, text: window.detail, editPath: window.editPath } : null;
  });
  const percent = (minute: number) => `${100 * minute / (summary?.day.durationMinutes ?? 1440)}%`;
  const questionCount = (count: number) => t(`${count} questions`, `${count} 題`);
  const cappedProfileIds = $derived(trafficShapingEnabled(configuration.traffic_shaping)
    ? new Set(configuration.traffic_shaping.profiles.filter(profile => profile.uplink_kbps !== null || profile.downlink_kbps !== null).map(profile => profile.id))
    : new Set<string>());
  function changeDay(value: number) { studyDay = value; selection = ''; }
  function resetReference() { studyDay = 1; selection = ''; }
</script>

<section class="overview" aria-labelledby={`${uid}-title`} data-testid="study-overview">
  <header class="overview__heading">
    <h2 id={`${uid}-title`}>{t('A day in this study', '研究的一天會如何進行')}</h2>
    <p>{t('See when each data source runs, when network speed changes, and when participants receive a questionnaire or notification.', '查看每種資料何時收集、網路速度何時改變，以及參與者何時收到問卷或通知。')}</p>
  </header>

  <details class="reference" data-testid="overview-reference">
    <summary>{t('Reference participant starts', '參考參與者開始於')} <strong>{startDate} {startTime}</strong> · {timeZone} <span>{t('Change scenario', '調整情境')}</span></summary>
    <div class="reference__fields">
      <label for={`${uid}-date`}>{t('Start date', '開始日期')}
        <input id={`${uid}-date`} type="date" bind:value={startDate} oninput={resetReference} data-testid="overview-start-date" />
      </label>
      <label for={`${uid}-time`}>{t('Start time', '開始時間')}
        <input id={`${uid}-time`} type="time" bind:value={startTime} oninput={resetReference} data-testid="overview-start-time" />
      </label>
      <label class="reference__zone" for={`${uid}-zone`}>{t('Device time zone (IANA)', '裝置時區（IANA）')}
        <input id={`${uid}-zone`} type="text" bind:value={timeZone} onchange={resetReference} spellcheck="false" placeholder="Asia/Taipei" data-testid="overview-time-zone" />
      </label>
    </div>
    <p>{t('These are preview controls. They do not set a participant’s start time or alter the study. Day 1 begins on the local activation date; the first and last days can be partial.', '這些是預覽控制，不會指定參與者的開始時間，也不會修改研究。第 1 天以啟動時的當地日期計算，第一天與最後一天可能僅涵蓋部分時段。')}</p>
  </details>

  {#if projection.error}
    <p class="overview__error" role="alert">{projection.error}</p>
  {/if}

  {#if summary}
    <div class="day-controls">
      <div class="day-controls__picker">
        <Button variant="quiet" label={t('Previous day', '前一天')} disabled={studyDay === 1} onclick={() => changeDay(studyDay - 1)} />
        <label for={`${uid}-day`} class="day-picker-label">{t('Study day', '研究日')}
          <select id={`${uid}-day`} value={studyDay} onchange={(event) => changeDay(Number(event.currentTarget.value))} data-testid="overview-day">
            {#each Array.from({ length: summary.day.totalDays }, (_, index) => index + 1) as day}
              <option value={day}>{t(`Day ${day}`, `第 ${day} 天`)}</option>
            {/each}
          </select>
        </label>
        <Button variant="quiet" label={t('Next day', '後一天')} disabled={studyDay === summary.day.totalDays} onclick={() => changeDay(studyDay + 1)} />
      </div>
      <p class="day-controls__date" aria-live="polite">{summary.day.date} · {timeZone}<span>{t(`${summary.day.totalDays} local dates · ${configuration.duration_hours} calendar hours`, `${summary.day.totalDays} 個當地日期 · ${configuration.duration_hours} 日曆小時`)}</span></p>
    </div>

    {#each summary.warnings as warning}<p class="overview__warning" role="status">{warning}</p>{/each}

    <div class="legend" aria-label={t('Timeline legend', '時間軸圖例')}>
      <span><i class="legend__active"></i>{t('Active profile', '啟用設定檔')}</span>
      {#if cappedProfileIds.size}<span><i class="legend__capped"></i>{t('Rate cap active', '流量上限生效')}</span>{/if}
      <span><i class="legend__inactive"></i>{t('Stopped / outside study', '停止／研究時段外')}</span>
      <span><i class="legend__conditional"></i>{t('Depends on conditions', '依條件決定')}</span>
      <span><i class="legend__random"></i>{t('Possible random window', '隨機可能時段')}</span>
      <span><i class="legend__event"></i>{t('Scheduled occurrence', '排定事件')}</span>
    </div>

    <p class="chart-hint" id={`${uid}-chart-hint`}>{t('Select a band or a time marker for details. On a small screen, scroll the timeline horizontally or use the daily agenda below.', '選取色帶或時間標記以查看細節。小螢幕可左右捲動時間軸，或查看下方的每日明細。')}</p>
    <!-- svelte-ignore a11y_no_noninteractive_tabindex (The horizontal timeline must be keyboard-scrollable.) -->
    <div class="chart-scroll" role="region" aria-label={t('Daily timeline', '每日時間軸')} aria-describedby={`${uid}-chart-hint`} tabindex="0">
      <div class="chart" data-testid="overview-chart">
        <div class="chart__axis">
          <div class="chart__axis-label">{t('Local time', '當地時間')}</div>
          <div class="axis">
            {#each summary.day.ticks as tick}
              <span class:first={tick.minute === 0} class:last={tick.minute === summary.day.durationMinutes} style:left={percent(tick.minute)}>{tick.label}</span>
            {/each}
          </div>
        </div>
        {#each summary.lanes as lane (lane.id)}
          <div class="chart__row" data-testid={`overview-lane-${lane.resourceId}`}>
            <div class="chart__label"><strong>{lane.label}</strong><span title={lane.detail}>{lane.detail}</span></div>
            <div class="track">
              {#each summary.day.ticks as tick}<i class="track__grid" style:left={percent(tick.minute)}></i>{/each}
              {#each lane.segments as segment, index}
                <button type="button" class="band" class:band--active={segment.state === 'active'} class:band--conditional={segment.state === 'conditional'}
                  class:band--capped={lane.kind === 'actuator' && segment.state === 'active' && segment.profileIds.length === 1 && cappedProfileIds.has(segment.profileIds[0] ?? '')}
                  class:band--outside={segment.state === 'outside-study'} class:band--selected={selection === `${lane.id}:${index}`}
                  style:left={percent(segment.startMinute)} style:width={percent(segment.endMinute - segment.startMinute)}
                  title={`${segment.startTime}–${segment.endTime} · ${segment.detail}`}
                  aria-label={`${lane.label}, ${segment.startTime}–${segment.endTime}, ${segment.label}, ${segment.detail}`}
                  onclick={() => selection = `${lane.id}:${index}`} onfocus={() => selection = `${lane.id}:${index}`}>
                  <span>{segment.label}</span>
                </button>
              {/each}
            </div>
          </div>
        {/each}
        {#if !summary.lanes.length}<p class="chart__empty">{t('Add a data source or network speed profile to see its collection periods.', '加入資料來源或網路速度設定檔，即可查看啟用時段。')}</p>{/if}
        {#each eventGroups as group (group.id)}
          <div class="chart__row chart__row--events">
            <div class="chart__label"><strong>{group.title}</strong><span>{group.questionCount ? questionCount(group.questionCount) : t('Notification', '通知')}</span></div>
            <div class="track track--events">
              {#each summary.day.ticks as tick}<i class="track__grid" style:left={percent(tick.minute)}></i>{/each}
              {#each group.windows as window}
                <button type="button" class="band band--random" style:left={percent(window.startMinute)} style:width={percent(window.endMinute - window.startMinute)}
                  title={window.detail} aria-label={`${window.title}, ${window.startTime}–${window.endTime}, ${t('possible random window', '隨機可能時段')}`}
                  onclick={() => selection = window.id} onfocus={() => selection = window.id}><span>{t('Random', '隨機')}</span></button>
              {/each}
              {#each group.events as event}
                <button type="button" class="event" class:event--conditional={event.certainty === 'conditional'} style:left={percent(event.atMinute)}
                  title={`${event.localTime} · ${event.title}\n${event.detail}`}
                  aria-label={`${event.localTime}, ${event.title}, ${event.questionCount ? questionCount(event.questionCount) : t('notification', '通知')}, ${event.certainty === 'conditional' ? t('conditional', '須符合條件') : t('scheduled', '排定')}`}
                  onclick={() => selection = event.id} onfocus={() => selection = event.id}>
                  <span class="event__dot"></span>{#if group.events.length <= 12}<span class="event__time">{event.localTime}</span>{/if}
                </button>
              {/each}
            </div>
          </div>
        {/each}
      </div>
    </div>

    {#if details}
      <div class="selection" aria-live="polite" data-testid="overview-selection">
        <div><strong>{details.title}</strong><p>{details.text}</p></div>
        {#if onedit}<Button variant="ghost" label={t('Edit setting', '編輯設定')} onclick={() => onedit?.(details.editPath)} />{/if}
      </div>
    {/if}

    <details class="agenda" open data-testid="overview-agenda">
      <summary>{t('Daily agenda', '每日明細')} <span>{t('All times and collection periods', '所有事件與收集時段')}</span></summary>
      <div class="agenda__content">
        <h3>{t('Questionnaires and notifications', '問卷與通知')}</h3>
        {#if !summary.events.length && !summary.randomWindows.length}
          <p class="empty">{t('No fixed or random schedule appears on this day. Event-dependent rules can still activate; review them below.', '這一天沒有固定或隨機排程。依事件觸發的規則仍可能啟動，請查看下方規則。')}</p>
        {:else}
          <ul class="agenda__events">
            {#each summary.events as event}
              <li>
                <time datetime={event.instant}>{event.localTime}</time>
                <div><strong>{event.title}</strong><p>{event.questionCount ? questionCount(event.questionCount) : t('Notification', '通知')} · {event.certainty === 'conditional' ? t('Only if conditions and remaining limits allow', '須符合條件且尚有剩餘次數') : t('Scheduled in this uninterrupted scenario', '在此持續執行情境下排定')}</p></div>
                <Button variant="ghost" label={t('Details', '細節')} onclick={() => selection = event.id} />
              </li>
            {/each}
            {#each summary.randomWindows as window}
              <li><span class="agenda__time">{window.startTime}–{window.endTime}</span><div><strong>{window.title}</strong><p>{t('Possible random window', '隨機可能時段')}{window.questionCount ? ` · ${questionCount(window.questionCount)}` : ''}</p></div><Button variant="ghost" label={t('Details', '細節')} onclick={() => selection = window.id} /></li>
            {/each}
          </ul>
        {/if}
        <details class="collection-list">
          <summary>{t('Collection and network speed periods', '資料收集與網路速度時段')}</summary>
          {#each summary.lanes as lane}
            <div class="collection-list__lane"><h4>{lane.label} <span>{lane.detail}</span></h4>
              <ul>{#each lane.segments as segment}<li><span class="agenda__time">{segment.startTime}–{segment.endTime}</span><span>{segment.detail}</span></li>{/each}</ul>
            </div>
          {/each}
        </details>
      </div>
    </details>

    <details class="rules" data-testid="overview-rules">
      <summary>{t('All study rules', '所有研究規則')} <span>{summary.rules.length} · {t('Including event-dependent activity', '包含依事件決定的活動')}</span></summary>
      <p class="rules__intro">{t('Rules can depend on events, continuous history, guards and previous activations. Their exact times require device observations. Every rule is listed here, including rules that do not activate on the selected day.', '規則可能依事件、連續歷史、守衛條件及先前啟動次數決定，確切時刻需要裝置觀測才能判定。此處列出所有規則，包含所選日期未啟動的規則。')}</p>
      {#each summary.rules as rule}
        <details class="study-rule" data-testid="overview-rule">
          <summary><code>{rule.id}</code>{#if rule.conditional}<span class="rule__conditional">{t('Depends on events / history', '依事件／歷史決定')}</span>{/if}</summary>
          <p>{rule.summary}</p>
          {#if onedit}<Button variant="ghost" label={t('Edit rule', '編輯規則')} onclick={() => onedit?.(rule.editPath)} />{/if}
        </details>
      {/each}
    </details>

    <details class="assumptions" data-testid="overview-assumptions">
      <summary><Icon name="info" size={18} />{t('How to read this projection', '如何理解此預覽')} <span>{t('Clocks, pauses and time zones', '時鐘、暫停與時區')}</span></summary>
      <ul>{#each summary.assumptions as assumption}<li>{assumption}</li>{/each}</ul>
    </details>
  {/if}
</section>

<style>
  .overview { display: flex; flex-direction: column; gap: var(--sp-6); min-inline-size: 0; }
  .overview__heading { display: grid; gap: var(--sp-4); }
  h2 { font-size: var(--type-title); font-weight: var(--w-bold); }
  .overview__heading p, .reference p, .rules__intro { color: var(--ink-soft); max-inline-size: 72ch; }
  summary { cursor: pointer; min-block-size: var(--tap-min); padding-block: var(--sp-5); font-weight: var(--w-medium); }
  summary span { color: var(--ink-soft); font-weight: var(--w-regular); font-size: var(--type-fine); margin-inline-start: var(--sp-4); }
  summary:hover { color: var(--accent-ink); }
  .reference { padding: 0 var(--sp-6); background: var(--surface-sunk); border-radius: var(--r-field); }
  .reference__fields { display: flex; gap: var(--sp-5); flex-wrap: wrap; margin-block: var(--sp-2) var(--sp-5); }
  label { display: flex; flex-direction: column; gap: var(--sp-2); font-size: var(--type-fine); color: var(--ink-soft); }
  input, select { border: var(--line-hair) solid var(--rule); border-radius: var(--r-field); min-block-size: var(--tap-min); padding: var(--sp-4) var(--sp-5); background: var(--surface); color: var(--ink); caret-color: var(--accent-ink); }
  input:hover, select:hover { border-color: var(--accent-ink); }
  input::placeholder { color: var(--ink-faint); }
  .reference__zone { flex: 1; min-inline-size: 12rem; }
  .reference p { padding-block-end: var(--sp-6); font-size: var(--type-fine); }
  .day-controls { display: flex; justify-content: space-between; align-items: end; flex-wrap: wrap; gap: var(--sp-5); }
  .day-controls__picker { display: flex; gap: var(--sp-4); align-items: end; }
  .day-controls__date { font-variant-numeric: tabular-nums; font-weight: var(--w-medium); }
  .day-controls__date span { display: block; font-size: var(--type-fine); color: var(--ink-soft); font-weight: var(--w-regular); }
  .overview__error, .overview__warning { padding: var(--sp-5) var(--sp-6); border-radius: var(--r-field); }
  .overview__error { background: var(--danger-wash); color: var(--danger-ink); }
  .overview__warning { background: var(--caution-wash); color: var(--caution-ink); }
  .legend { display: flex; flex-wrap: wrap; gap: var(--sp-4) var(--sp-6); font-size: var(--type-fine); color: var(--ink-soft); }
  .legend > span { display: inline-flex; align-items: center; gap: var(--sp-3); }
  .legend i { inline-size: var(--sp-5); block-size: var(--sp-5); border-radius: var(--r-chip); border: var(--line-hair) solid var(--rule); }
  .legend__active { background: var(--accent-wash); border-color: var(--accent-ink) !important; }
  .legend__capped { background: var(--caution-ink); border-color: var(--caution-ink) !important; }
  .legend__inactive { background: var(--surface-sunk); }
  .legend__conditional { background: var(--caution-wash); border-color: var(--caution-ink) !important; border-style: dashed !important; }
  .legend__random { background: var(--voice-wash); border-color: var(--voice-ink) !important; border-style: dashed !important; }
  .legend .legend__event { border-radius: var(--r-pill); background: var(--voice-ink); }
  .chart-hint { color: var(--ink-soft); font-size: var(--type-fine); }
  .chart-scroll { overflow-x: auto; padding-block: var(--sp-2) var(--sp-5); scrollbar-color: var(--rule) var(--surface); }
  .chart { min-inline-size: 46rem; padding-inline-end: var(--sp-5); }
  .chart__axis, .chart__row { display: grid; grid-template-columns: 12rem minmax(0, 1fr); }
  .chart__axis { min-block-size: var(--sp-8); color: var(--ink-soft); font-size: var(--type-fine); }
  .axis { position: relative; margin-inline: var(--sp-2); }
  .axis span { position: absolute; transform: translateX(-50%); font-variant-numeric: tabular-nums; white-space: nowrap; }
  .axis .first { transform: none; }
  .axis .last { transform: translateX(-100%); }
  .chart__row { min-block-size: 3.75rem; border-block-start: var(--line-hair) solid var(--rule); }
  .chart__label { display: flex; justify-content: center; flex-direction: column; gap: var(--sp-1); padding: var(--sp-4) var(--sp-5) var(--sp-4) 0; }
  .chart__label strong { font-size: var(--type-fine); font-weight: var(--w-medium); overflow-wrap: anywhere; }
  .chart__label > span { font-size: var(--type-micro); color: var(--ink-soft); white-space: nowrap; text-overflow: ellipsis; overflow: hidden; }
  .track { position: relative; margin-inline: var(--sp-2); }
  .track__grid { position: absolute; inset-block: 0; border-inline-start: var(--line-hair) solid var(--rule); pointer-events: none; }
  .band { position: absolute; inset-block: var(--sp-4); display: flex; align-items: center; justify-content: center; min-block-size: var(--tap-min); padding-inline: var(--sp-4); color: var(--ink-soft); background: var(--surface-sunk); border: var(--line-hair) solid var(--rule); border-radius: var(--r-chip); font-size: var(--type-fine); }
  .band span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .band--active { color: var(--accent-ink); background: var(--accent-wash); border-color: var(--accent-ink); }
  .band--capped { color: var(--surface); background: var(--caution-ink); border-color: var(--caution-ink); font-weight: var(--w-bold); }
  .band--conditional { color: var(--caution-ink); background: var(--caution-wash); border: var(--line-solid) dashed var(--caution-ink); }
  .band--random { color: var(--voice-ink); background: var(--voice-wash); border: var(--line-solid) dashed var(--voice-ink); }
  .band--outside { background: var(--surface); border-style: dashed; }
  .band:hover, .band--selected { filter: brightness(.94); }
  .band:active { filter: brightness(.88); }
  .chart__row--events { min-block-size: 4.5rem; }
  .event { position: absolute; inset-block: var(--sp-4); transform: translateX(-50%); min-block-size: var(--tap-min); min-inline-size: 1.25rem; display: flex; flex-direction: column; align-items: center; gap: var(--sp-3); justify-content: center; color: var(--voice-ink); z-index: 1; }
  .event__dot { display: block; inline-size: var(--sp-5); block-size: var(--sp-5); border-radius: var(--r-pill); background: var(--voice-ink); border: var(--line-solid) solid var(--voice-ink); }
  .event--conditional .event__dot { background: var(--caution-wash); border-color: var(--caution-ink); border-style: dashed; }
  .event__time { font-size: var(--type-micro); font-variant-numeric: tabular-nums; white-space: nowrap; }
  .event:hover .event__dot { transform: scale(1.2); }
  .event:active .event__dot { transform: scale(.9); }
  .chart__empty, .empty { color: var(--ink-soft); padding-block: var(--sp-5); }
  .selection { display: flex; align-items: start; justify-content: space-between; gap: var(--sp-5); padding: var(--sp-6); background: var(--surface-sunk); border-radius: var(--r-field); }
  .selection p, .study-rule > p { white-space: pre-line; overflow-wrap: anywhere; font-size: var(--type-fine); color: var(--ink-soft); margin-block-start: var(--sp-4); }
  .agenda, .rules, .assumptions { border-block-start: var(--line-hair) solid var(--rule); }
  .agenda__content { padding-block: var(--sp-4); }
  h3 { font-size: var(--type-body); font-weight: var(--w-medium); }
  .agenda__events { margin-block: var(--sp-4) var(--sp-6); }
  .agenda__events li { display: grid; grid-template-columns: 8rem minmax(0, 1fr) auto; gap: var(--sp-5); align-items: center; padding-block: var(--sp-5); border-block-end: var(--line-hair) solid var(--rule); }
  .agenda__events strong { font-weight: var(--w-medium); }
  .agenda__events p, .collection-list, .collection-list h4 span { color: var(--ink-soft); font-size: var(--type-fine); }
  time, .agenda__time { font-variant-numeric: tabular-nums; white-space: nowrap; font-size: var(--type-fine); }
  .collection-list__lane { padding-block: var(--sp-5); }
  .collection-list h4 { color: var(--ink); font-weight: var(--w-medium); }
  .collection-list h4 span { margin-inline-start: var(--sp-4); font-weight: var(--w-regular); }
  .collection-list li { display: grid; grid-template-columns: 8rem minmax(0, 1fr); gap: var(--sp-5); padding-block-start: var(--sp-4); overflow-wrap: anywhere; }
  .rules__intro { margin-block: var(--sp-4) var(--sp-5); font-size: var(--type-fine); }
  .study-rule { margin-inline-start: var(--sp-5); border-block-end: var(--line-hair) solid var(--rule); padding-block-end: var(--sp-4); }
  .study-rule code { font-size: var(--type-fine); overflow-wrap: anywhere; }
  .rule__conditional { color: var(--caution-ink); }
  .assumptions summary :global(svg) { display: inline-block; vertical-align: middle; margin-inline-end: var(--sp-3); }
  .assumptions li { padding-block: var(--sp-4); padding-inline-start: var(--sp-5); font-size: var(--type-fine); color: var(--ink-soft); max-inline-size: 76ch; }
  @media (max-width: 640px) {
    .reference { padding-inline: var(--sp-5); }
    .reference__fields > label { flex: 1 1 9rem; }
    .reference__fields input { min-inline-size: 0; inline-size: 100%; }
    .day-controls__picker { gap: var(--sp-2); inline-size: 100%; justify-content: space-between; }
    .day-picker-label { min-inline-size: 5.5rem; }
    .day-controls__date { font-size: var(--type-fine); }
    .agenda__events li { grid-template-columns: 1fr auto; gap: var(--sp-2) var(--sp-4); }
    .agenda__events li > time, .agenda__events li > .agenda__time { grid-column: 1 / -1; }
    .selection { flex-direction: column; }
    .collection-list li { grid-template-columns: 1fr; gap: var(--sp-1); }
    .collection-list h4 span { display: block; margin-inline-start: 0; }
    .legend { gap: var(--sp-4) var(--sp-5); }
    summary > span { display: block; margin-inline-start: 0; padding-block-start: var(--sp-2); }
  }
</style>
