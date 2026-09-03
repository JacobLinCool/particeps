/**
 * The wrap detector.
 *
 * The rule it enforces: no sentence the site itself writes may need to wrap. Every line of
 * interface text has to fit on one line at the width it is actually rendered. That is a constraint
 * on how long a string may be, it is measurable, and this is the thing that measures it.
 *
 * It walks both locales, three routes, every step of the researcher's four-step page, and a set of
 * viewport widths, and reports every leaf run of text that occupies more than one line — with the
 * width that was available and how many characters fitted on the first line, because the next
 * person to touch those strings is rewriting them and needs to know the budget.
 *
 *   pnpm build && pnpm exec http-server build -p 4173   # or any static server
 *   node e2e/one-line.mjs
 *   REPORT_JSON=/tmp/wrap.json node e2e/one-line.mjs    # same run, machine-readable
 *
 * Exit code: non-zero if anything on `/researcher/` wraps. The other two routes are reported and
 * do not gate, because the researcher page is the one under specification.
 *
 * ---------------------------------------------------------------------------------------------
 * How a line is counted, and why this way
 *
 * Two methods were available. Comparing `clientHeight` against the computed `line-height` is one
 * division and is wrong more often than it is right here: the box is not the text. Padding,
 * borders, `min-block-size`, an icon sitting in the same box, a flex parent stretching a child,
 * `align-items: stretch` in the collector grid, and `line-height: normal` (which resolves to a
 * used value the ratio does not know) all move `clientHeight` without moving a single line of
 * text. Every one of those exists on this site.
 *
 * So this uses the second method: a `Range` over each text node, `getClientRects()`, and a count
 * of distinct line boxes. A range over a text node yields one rect per line box the text occupies,
 * measured from the text itself and not from the box around it — which is exactly the question.
 * Rects are grouped into lines by vertical overlap rather than by an exact top offset, because a
 * single visual line containing two font sizes (a label beside a counter, a unit beside a number)
 * produces rects with different tops that are plainly one line to a reader.
 * ---------------------------------------------------------------------------------------------
 */

import { chromium } from 'playwright';
import { writeFileSync } from 'node:fs';
import { en as researcherEn } from '../src/lib/i18n/en.ts';
import { zhTW as researcherZhTw } from '../src/lib/i18n/zh-TW.ts';
import { en as participantEn, zhTW as participantZhTw } from '../src/lib/participant/copy.ts';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:4173';
const REPORT_JSON = process.env.REPORT_JSON ?? '';

/**
 * A wide desktop, a mid desktop, and the 1280px laptop. `.wrap` is capped at 68rem, so these will
 * often agree — and when they do, that agreement is the finding: the budget a string is written to
 * does not get more generous on a bigger screen.
 */
const WIDTHS = [1920, 1440, 1280];

const LOCALES = [
  { id: 'en', browser: 'en-US' },
  { id: 'zh-TW', browser: 'zh-TW' }
];

/**
 * The researcher page holds its step in component state, not in the URL, so a view is a route plus
 * whatever has to be clicked to reach it. `expand` opens the parts of the Study step that are
 * behind a switch or add action: seven collectors, both intervention action types, and scheduled
 * delivery. Those reveal labels and
 * hints the site wrote, so leaving them closed would inventory half the step.
 */
const VIEWS = [
  { id: '/', route: '/' },
  { id: '/researcher/ · keys', route: '/researcher/', step: 'keys' },
  { id: '/researcher/ · study', route: '/researcher/', step: 'study' },
  { id: '/researcher/ · study (all sources on)', route: '/researcher/', step: 'study', expand: true },
  { id: '/researcher/ · sign', route: '/researcher/', step: 'sign' },
  { id: '/researcher/ · files', route: '/researcher/', step: 'files' },
  { id: '/researcher/ · read', route: '/researcher/', step: 'read' },
  { id: '/participant/', route: '/participant/' }
];

/* ---------------------------------------------------------------------------------------------
 * The catalogue, reversed.
 *
 * A violation is only actionable if it names the string that has to be rewritten, so every plain
 * string in both catalogues is indexed by its text. Message *functions* — `sign.size`,
 * `sign.blocked`, `control.stepPosition`, the `issue.*_range` pair — are not indexed: they render
 * numbers this run does not know. Those come back as `(untraced)`, and their text is printed in
 * full so a human can still place them.
 * ------------------------------------------------------------------------------------------- */

function flatten(node, prefix, into) {
  for (const [key, value] of Object.entries(node)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (typeof value === 'string') into.push([path, value]);
    else if (value && typeof value === 'object') flatten(value, path, into);
  }
  return into;
}

function catalogue(localeId) {
  const researcher = localeId === 'en' ? researcherEn : researcherZhTw;
  const participant = localeId === 'en' ? participantEn : participantZhTw;
  const index = new Map();
  const add = (file, pairs) => {
    for (const [path, text] of pairs) {
      const trimmed = text.trim();
      if (!trimmed) continue;
      if (!index.has(trimmed)) index.set(trimmed, []);
      index.get(trimmed).push(`${file}:${path}`);
    }
  };
  add(localeId === 'en' ? 'i18n/en.ts' : 'i18n/zh-TW.ts', flatten(researcher, '', []));
  add('participant/copy.ts', flatten(participant, '', []));
  return index;
}

/** Exact match first; then the longest catalogue string the rendered text contains, so a line
 *  built from a message plus a rendered value still names its message. */
function trace(index, text) {
  const normalised = text.replace(/\s+/g, ' ').trim();
  const exact = index.get(normalised);
  if (exact) return exact;
  let best = null;
  let bestKeys = null;
  for (const [candidate, keys] of index) {
    const flat = candidate.replace(/\s+/g, ' ');
    if (flat.length < 12) continue;
    // Only a line that is *mostly* this message. A short catalogue string sitting inside a long
    // unrelated run is a coincidence, not a trace, and a wrong key sends the rewriter to the
    // wrong file.
    if (normalised.length > flat.length * 1.8) continue;
    if (normalised.includes(flat) && (!best || flat.length > best.length)) {
      best = flat;
      bestKeys = keys;
    }
  }
  return best ? bestKeys.map((key) => `${key} (+ a rendered value on the same line)`) : null;
}

/* ---------------------------------------------------------------------------------------------
 * The scan, run inside the page.
 * ------------------------------------------------------------------------------------------- */

function scanPage({ route }) {
  const SKIP_TAGS = new Set([
    'SCRIPT',
    'STYLE',
    'NOSCRIPT',
    'TEMPLATE',
    'TITLE',
    'SVG',
    'OPTION',
    'OPTGROUP',
    'HEAD',
    'META',
    'LINK'
  ]);

  const visible = (el) => {
    if (SKIP_TAGS.has(el.tagName)) return false;
    if (el.closest('svg')) return false;
    if (typeof el.checkVisibility === 'function') {
      if (
        !el.checkVisibility({
          contentVisibilityAuto: true,
          opacityProperty: true,
          visibilityProperty: true
        })
      ) {
        return false;
      }
    }
    const rect = el.getBoundingClientRect();
    // Screen-reader-only text is a 1px clipped box; the skip link is translated off the top.
    if (rect.width < 2 || rect.height < 2) return false;
    // Off the *document*, not off the viewport. Anything scrolled past is still rendered text, and
    // testing this against the viewport silently dropped the whole top of the Study step the
    // moment a click scrolled the page down to reach a switch.
    if (rect.bottom + window.scrollY < 0 || rect.right + window.scrollX < 0) return false;
    const style = getComputedStyle(el);
    if (style.clipPath.includes('inset(50%)')) return false;
    return true;
  };

  const INLINE = /^(inline|ruby)/;

  const textCarrying = (el) => (el.textContent ?? '').trim().length > 0;

  /**
   * The display of every text-carrying box directly inside `el`.
   *
   * `display: contents` is why this is a function rather than a loop over `el.children`. Such an
   * element generates no box at all — SvelteKit's own body wrapper is one — so its children are
   * laid out as if it were not there and it has to be looked straight through. Treating it as an
   * inline child instead made `<body>` itself the leaf and the whole page one finding.
   *
   * A flex or grid item's computed display is blockified, which is what makes this work on a site
   * built out of flex rows: a label and its counter sitting side by side are two boxes, and each
   * is measured for itself rather than as one run of text that happens to share a line.
   */
  const childBoxes = (el) => {
    const displays = [];
    for (const child of el.children) {
      if (SKIP_TAGS.has(child.tagName)) continue;
      if (!textCarrying(child)) continue;
      const display = getComputedStyle(child).display;
      if (display === 'contents') {
        displays.push(...childBoxes(child));
        continue;
      }
      if (display === 'none') continue;
      if (!visible(child)) continue;
      displays.push(display);
    }
    return displays;
  };

  /**
   * A leaf: an element with text of its own, none of whose text-carrying element children is
   * block-level. Inline children are part of this element's own line boxes, so a paragraph with a
   * link in it is measured whole — measuring the link alone would miss the wrap the sentence
   * around it causes. Anything with a block-level text child is a container and is walked into.
   */
  const isLeaf = (el) =>
    textCarrying(el) && childBoxes(el).every((display) => INLINE.test(display));

  /** Every non-blank text node under `el`, with the offsets of its first and last non-space char. */
  const runs = (el) => {
    const found = [];
    const walker = document.createTreeWalker(el, NodeFilter.SHOW_TEXT);
    let node;
    while ((node = walker.nextNode())) {
      const data = node.data ?? '';
      if (!data.trim()) continue;
      if (node.parentElement && !visible(node.parentElement)) continue;
      const start = data.length - data.trimStart().length;
      const end = data.trimEnd().length;
      found.push({ node, start, end });
    }
    return found;
  };

  const rectsOf = (el) => {
    const collected = [];
    const range = document.createRange();
    for (const run of runs(el)) {
      range.setStart(run.node, run.start);
      range.setEnd(run.node, run.end);
      for (const rect of range.getClientRects()) {
        if (rect.width > 0.5 && rect.height > 0.5) collected.push(rect);
      }
    }
    return collected;
  };

  /**
   * Group rects into line boxes. Two rects share a line when either one's vertical centre falls
   * inside the other's band — a containment test rather than a growing band, so a column of
   * closely spaced lines cannot chain into one.
   */
  const countLines = (rects) => {
    const lines = [];
    for (const rect of rects) {
      const centre = rect.top + rect.height / 2;
      const line = lines.find((l) => {
        const lineCentre = l.top + (l.bottom - l.top) / 2;
        return (centre >= l.top && centre <= l.bottom) || (lineCentre >= rect.top && lineCentre <= rect.bottom);
      });
      if (line) {
        line.top = Math.min(line.top, rect.top);
        line.bottom = Math.max(line.bottom, rect.bottom);
      } else {
        lines.push({ top: rect.top, bottom: rect.bottom });
      }
    }
    return lines.length;
  };

  /**
   * How many characters of this element's text land on its first line. This is the budget: a
   * rewrite has to come in at or under it. Walked character by character, and only for elements
   * already known to wrap, so the cost is bounded by the size of the problem.
   */
  const firstLineChars = (el) => {
    const range = document.createRange();
    const found = runs(el);
    if (!found.length) return null;
    range.setStart(found[0].node, found[0].start);
    range.setEnd(found[0].node, Math.min(found[0].start + 1, found[0].end));
    const first = range.getBoundingClientRect();
    const band = first.top + first.height / 2;
    let count = 0;
    for (const run of found) {
      for (let i = run.start; i < run.end; i += 1) {
        range.setStart(run.node, i);
        range.setEnd(run.node, i + 1);
        const rect = range.getBoundingClientRect();
        // A space at a line break collapses to a zero-width rect; it belongs to the line before it.
        if (rect.width > 0.5 && (rect.top > band || rect.bottom < band)) return count;
        count += 1;
      }
    }
    return count;
  };

  const cssPath = (el) => {
    const parts = [];
    let node = el;
    while (node && node.nodeType === 1 && node !== document.body && parts.length < 6) {
      const testid = node.getAttribute('data-testid');
      if (testid) {
        parts.unshift(`[data-testid="${testid}"]`);
        break;
      }
      // `$props.id()` ids — `s4-hint`, `c14-control` — change on every build and name nothing.
      if (node.id && !/^[a-z]{1,2}\d+-/.test(node.id)) {
        parts.unshift(`#${node.id}`);
        break;
      }
      let part = node.tagName.toLowerCase();
      const cls = (node.getAttribute('class') ?? '')
        .split(/\s+/)
        .find((name) => name && !name.startsWith('svelte-') && !name.startsWith('s-'));
      if (cls) part += `.${cls}`;
      const parent = node.parentElement;
      if (parent) {
        const siblings = [...parent.children].filter((c) => c.tagName === node.tagName);
        if (siblings.length > 1) part += `:nth-of-type(${siblings.indexOf(node) + 1})`;
      }
      parts.unshift(part);
      node = node.parentElement;
    }
    return parts.join(' > ');
  };

  /* -------------------------------------------------------------------------------------------
   * Exemptions. Deliberate, and each one says why.
   *
   * The rule is about interface text the site itself writes. It is not about content a researcher
   * supplies, and it is not about the participant page's body prose — that page is a document to
   * be read, and a paragraph in a document is allowed to be a paragraph.
   *
   * Exempt findings are still collected and still printed, in a separate bucket, so that nothing
   * is quietly swallowed by a rule someone will want to argue with later.
   * ----------------------------------------------------------------------------------------- */
  const exemptionFor = (el) => {
    // 1. Anything a researcher typed, and the two textareas whose own content is theirs: the
    //    consent summary and the purpose. A textarea's value is a text node; it is never ours.
    if (el.tagName === 'TEXTAREA' || el.tagName === 'INPUT' || el.closest('textarea')) {
      return 'author-content: a field a researcher types into';
    }
    if (el.isContentEditable) return 'author-content: a field a researcher types into';

    // 2. The participant page's body prose. That page is one document, read top to bottom, and its
    //    paragraphs are prose rather than interface labels. Its header, its footer chrome, its
    //    headings, and every label, chip, button and table cell on it are still checked.
    if (route === '/participant/') {
      const main = el.closest('main');
      if (main && (el.tagName === 'P' || el.classList.contains('section__lead'))) {
        return 'participant-prose: body prose on the participant document';
      }
    }

    // 3. Identifiers and generated data rather than sentences: filenames, byte counts, canonical
    //    JSON, key fingerprints, an endpoint host echoed back. All of them are set in monospace on
    //    this site precisely because they are values and not language, and none of them can be
    //    shortened by rewriting a message.
    const family = getComputedStyle(el).fontFamily.toLowerCase();
    if (family.includes('mono') || el.closest('pre, code')) {
      return 'identifier: a value or generated data, not a sentence the site wrote';
    }

    return null;
  };

  const findings = [];
  const covered = new WeakSet();
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_ELEMENT);
  let el = document.body;
  do {
    if (covered.has(el)) continue;
    if (SKIP_TAGS.has(el.tagName)) continue;
    if (!visible(el)) continue;
    if (!isLeaf(el)) continue;

    // Claim the subtree: the inline children folded into this element's line boxes are already
    // measured here, and reporting them again would double-count one wrapped sentence.
    const inner = document.createTreeWalker(el, NodeFilter.SHOW_ELEMENT);
    let descendant;
    while ((descendant = inner.nextNode())) covered.add(descendant);

    const rects = rectsOf(el);
    if (!rects.length) continue;
    const lines = countLines(rects);
    if (lines < 2) continue;

    const style = getComputedStyle(el);
    const box = el.getBoundingClientRect();
    const padding = parseFloat(style.paddingLeft) + parseFloat(style.paddingRight);
    const border = parseFloat(style.borderLeftWidth) + parseFloat(style.borderRightWidth);
    const text = (el.textContent ?? '').replace(/\s+/g, ' ').trim();

    findings.push({
      selector: cssPath(el),
      tag: el.tagName.toLowerCase(),
      text,
      characters: [...text].length,
      lines,
      firstLineCharacters: firstLineChars(el),
      availableWidth: Math.round(box.width - padding - border),
      renderedWidth: Math.round(Math.max(...rects.map((r) => r.width))),
      lineHeight: style.lineHeight,
      exempt: exemptionFor(el)
    });
  } while ((el = walker.nextNode()));

  return findings;
}

/* ---------------------------------------------------------------------------------------------
 * Driving the browser.
 * ------------------------------------------------------------------------------------------- */

async function reachStep(page, step) {
  const rail = page.locator(`[data-testid="rail-${step}"]`);
  await rail.click();
  await page.waitForSelector(`[data-testid="step-${step}"]`);
  await page.waitForTimeout(350);
}

/** Opens everything on the Study step that hides behind a switch, so the inventory is the whole
 *  step rather than the half of it that happens to be on screen when the page loads. */
async function expandStudy(page) {
  const switches = page.locator('[data-testid^="collector-enable-"]');
  for (let i = 0; i < (await switches.count()); i += 1) {
    const one = switches.nth(i);
    if ((await one.getAttribute('aria-checked')) === 'false') await one.click();
  }
  await page.locator('[data-testid="add-notification"]').click();
  await page.locator('[data-testid="add-survey"]').click();
  const delivery = page.locator('#delivery [role="switch"]').first();
  if ((await delivery.getAttribute('aria-checked')) === 'false') await delivery.click();
  await page.waitForTimeout(500);
}

const browser = await chromium.launch();
const rows = [];
const pageErrors = [];

for (const locale of LOCALES) {
  const context = await browser.newContext({ locale: locale.browser });
  // The catalogue in force is `localStorage['particeps.locale']`, read by `app.html` before first paint
  // and by `i18n.svelte.ts` after it. Setting it makes the locale deterministic rather than a
  // guess about what Chromium reports in `navigator.languages`.
  await context.addInitScript(
    (value) => window.localStorage.setItem('particeps.locale', value),
    locale.id
  );

  for (const width of WIDTHS) {
    for (const view of VIEWS) {
      // Each view gets a fresh document. The researcher route deliberately protects generated
      // private keys with beforeunload; reusing one page would make the test's own next navigation
      // race that protection and occasionally measure the previous step instead.
      const page = await context.newPage();
      await page.setViewportSize({ width, height: 1400 });
      page.on('pageerror', (error) =>
        pageErrors.push(`${locale.id} ${width}px ${view.id}: ${error}`)
      );

      try {
        await page.goto(`${ORIGIN}${view.route}`, { waitUntil: 'networkidle' });
        await page.waitForTimeout(250);
        if (view.step) await reachStep(page, view.step);
        if (view.expand) await expandStudy(page);
        // Fonts settle after first paint; measuring before they do measures the fallback face.
        // Back to the top afterwards, because reaching a control scrolled the page to it.
        await page.evaluate(async () => {
          await document.fonts.ready;
          window.scrollTo(0, 0);
        });
        await page.waitForTimeout(150);

        const findings = await page.evaluate(scanPage, { route: view.route });
        for (const finding of findings) {
          rows.push({ ...finding, locale: locale.id, width, view: view.id, route: view.route });
        }
      } finally {
        await page.close();
      }
    }
  }

  await context.close();
}

await browser.close();

/* ---------------------------------------------------------------------------------------------
 * The report.
 * ------------------------------------------------------------------------------------------- */

const index = { en: catalogue('en'), 'zh-TW': catalogue('zh-TW') };
for (const row of rows) row.keys = trace(index[row.locale], row.text) ?? ['(untraced)'];

const violations = rows.filter((row) => !row.exempt);
const exempted = rows.filter((row) => row.exempt);

/** One line of interface text is one entry, however many locales and widths it wrapped at. */
function group(list) {
  const byKey = new Map();
  for (const row of list) {
    const key = `${row.keys.join(' | ')} ${row.locale}`;
    if (!byKey.has(key)) byKey.set(key, { keys: row.keys, locale: row.locale, rows: [] });
    byKey.get(key).rows.push(row);
  }
  return [...byKey.values()].sort((a, b) => a.keys[0].localeCompare(b.keys[0]));
}

const bar = '-'.repeat(96);

function report(title, list) {
  console.log(`\n${bar}\n${title}\n${bar}`);
  if (!list.length) {
    console.log('  nothing');
    return;
  }
  for (const entry of group(list)) {
    const first = entry.rows[0];
    const widths = [...new Set(entry.rows.map((row) => row.width))].sort((a, b) => a - b);
    const views = [...new Set(entry.rows.map((row) => row.view))];
    const budget = Math.min(...entry.rows.map((row) => row.firstLineCharacters ?? Infinity));
    console.log(`\n${entry.keys.join('\n')}   [${entry.locale}]`);
    console.log(`  text        ${JSON.stringify(first.text)}`);
    console.log(
      `  budget      ${first.characters} characters now, ${Number.isFinite(budget) ? budget : '?'} fit on the first line ` +
        `→ cut ${Number.isFinite(budget) ? Math.max(0, first.characters - budget) : '?'}`
    );
    console.log(
      `  space       ${Math.min(...entry.rows.map((r) => r.availableWidth))}px available, ` +
        `${Math.max(...entry.rows.map((r) => r.lines))} lines used, line-height ${first.lineHeight}`
    );
    console.log(`  where       ${views.join(', ')}`);
    console.log(`  widths      ${widths.map((w) => `${w}px`).join(', ')}`);
    console.log(`  selector    ${first.selector}`);
    if (entry.rows[0].exempt) console.log(`  exempt      ${entry.rows[0].exempt}`);
  }
}

const researcherViolations = violations.filter((row) => row.route === '/researcher/');
const otherViolations = violations.filter((row) => row.route !== '/researcher/');

report('WRAPS ON /researcher/ — these fail the build', researcherViolations);
report('WRAPS ELSEWHERE — reported, not gating', otherViolations);
report('EXEMPT, and wrapping anyway — listed so no exemption hides quietly', exempted);

const distinct = (list) => new Set(list.map((row) => `${row.keys.join('|')} ${row.locale}`)).size;

console.log(`\n${bar}`);
console.log(
  `${VIEWS.length} views × ${LOCALES.length} locales × ${WIDTHS.length} widths = ` +
    `${VIEWS.length * LOCALES.length * WIDTHS.length} page scans`
);
for (const view of VIEWS) {
  const here = violations.filter((row) => row.view === view.id);
  const count = distinct(here);
  console.log(`  ${count === 0 ? 'clean' : `${count} wrapping`.padStart(5)}  ${view.id}`);
}
console.log('');
console.log(`  ${distinct(researcherViolations)} wrapping strings on /researcher/ (${researcherViolations.length} occurrences)`);
console.log(`  ${distinct(otherViolations)} wrapping strings elsewhere (${otherViolations.length} occurrences)`);
console.log(`  ${distinct(exempted)} exempt strings wrapped and were not counted`);

if (pageErrors.length) {
  console.log(`\nthe page logged errors:\n  ${pageErrors.join('\n  ')}`);
}

if (REPORT_JSON) {
  writeFileSync(REPORT_JSON, JSON.stringify({ rows, violations, exempted }, null, 2));
  console.log(`\nwrote ${REPORT_JSON}`);
}

if (researcherViolations.length) {
  console.error(`\nFAIL — ${distinct(researcherViolations)} strings on /researcher/ need more than one line.`);
  process.exit(1);
}
console.log('\nPASS — every line of interface text fits on one line.');
