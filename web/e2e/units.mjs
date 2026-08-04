/**
 * The storage-unit detector.
 *
 * The rule it enforces: a researcher is never shown a number in the unit the *file* stores. The
 * schema counts a sampling period in microseconds, an interval in milliseconds, a quota in bytes; a
 * person states a rate in hertz, an interval in seconds or minutes, a quota in mebibytes. Every
 * conversion belongs to `routes/researcher/scales.ts` and to nothing else, and the visible surface
 * of that rule is simple enough to test from outside: `100000`, `1000000` and `1073741824` must not
 * appear on a control, and neither must any other value the control itself humanises elsewhere.
 *
 *   pnpm build && python3 -m http.server 4173 --directory build   # or any static server
 *   pnpm e2e:units
 *   ORIGIN=http://localhost:5173 pnpm e2e:units                   # against the dev server
 *
 * Exit code: non-zero if any control on `/researcher/` shows a storage-unit number.
 *
 * It drives the researcher page the way a person would — reach the Study step, switch on all twelve
 * collectors, add an intervention, switch on delivery — so that every control the site has is mounted, and
 * then reads what is actually on screen. It does this in both locales, and it does it again after
 * clicking every preset chip on every control, so the assertions cover the values the page itself
 * advertises rather than only the ones it opens on.
 *
 * ---------------------------------------------------------------------------------------------
 * The four checks, and what each one is worth
 *
 * A. NAMED. Every number a control shows is shown with a word for its unit. A bare `24` beside
 *    `1 day` does not say the 24 is hours; a bare `60` does not say it is hertz. Exactly `0` is
 *    exempt, because zero is its own answer — an unbatched sensor, an immediate delivery — and
 *    "0 minutes" attaches a unit to the absence of a quantity. The number box also has to carry the
 *    unit in its accessible name, because the affix beside it is not read out.
 *
 * B. IN THE DECLARED SPACE. A control with a number box declares its own control space in that
 *    box's `min` and `max` attributes. No number anywhere on the control may fall outside it. This
 *    is the check that catches `100000` on a 1–200 Hz box and `1000000` on a 0–60 s box, and it
 *    needs no knowledge of the schema at all: the page states the range and is held to it.
 *
 * C. NOT A VALUE THE CONTROL ITSELF HUMANISES. On a control with no number box the adapter is the
 *    identity — `scales.ts` gives those controls a ladder in the schema's own unit — so a preset
 *    chip's `data-testid` is a stored value and its label is that value humanised. When the two
 *    differ, testid `1073741824` against label `1 GiB`, the control has already ruled that nobody
 *    reads the stored form, and it must then appear nowhere else on that control. Self-calibrating:
 *    the page supplies both halves of the comparison.
 *
 *    Not applied to a boxed control, where a chip's testid is a control-space value and not a
 *    stored one. `60` on the report latency box is sixty seconds and the `1 min` beside it is the
 *    same quantity said a second way, which is the design and not a leak. Check B is what covers
 *    the boxed controls, and it covers them completely: every stored value those two fields can
 *    hold is a microsecond count far outside the box's own range.
 *
 * D. NO COARSE-UNIT NUMBER IS OUT OF SCALE. A control with no number box shows nothing but
 *    humaniser output, and every humaniser on this page converts at 60, 24, 365 or 1024. So no
 *    correct reading from one can be 1024 or larger: 1023 MiB becomes a gibibyte, 60 minutes an
 *    hour, 24 hours a day, and a year is the longest study the schema allows. A number at or above
 *    1024 on such a control is a raw stored value that escaped its adapter.
 *
 * E. PLAUSIBLE FOR THE WORD BESIDE IT. Checks B, C and D all read the control's own claims, so all
 *    three go quiet if an adapter is removed outright and its box goes back to declaring the
 *    schema's range: a box reading `100000` between `5000` and `1000000` with `Hz` beside it
 *    satisfies every one of them. That is the original defect, so one check has to know something
 *    the page cannot tell it. E is that check, and its knowledge comes from `BOUNDS` in
 *    `src/lib/adc/types.ts` and the unit words from the two catalogues — the schema and the
 *    vocabulary, not a list of magic numbers. A rate is at most 200 Hz because the schema's fastest
 *    period is 5000 µs; a displacement is at most 10 000 m because the schema says so; `sec`,
 *    `min`, `h` and the day word convert at 60, 24 and 365; a binary prefix carries at most 1023 of
 *    the unit below it. And `µs` and `ms` are the file's own words: a control that puts a number
 *    beside either has already lost the argument, whatever the number is.
 *
 * ---------------------------------------------------------------------------------------------
 * What this cannot catch — read this before trusting a green run
 *
 * This is a heuristic over rendered text. Four of the five checks know only what the page shows
 * and what the page claims about itself; the fifth knows the schema's bounds and the two unit
 * catalogues. None of them can decide whether a number is *right*. Specifically:
 *
 *   - A stored value small enough to pass for a control-space value goes straight through. A poll
 *     interval reading `720 min` where `12 h` was meant is a real defect and this file is silent
 *     on it: 720 is a legal minute count, it is under the ceiling for `min`, it carries its unit,
 *     and no chip on that control contradicts it. `tests/scales.spec.ts` is what walks every rung
 *     of every lattice.
 *   - A wrong conversion that lands inside the declared range goes through. A box showing `20 Hz`
 *     for a period that stores 100 000 µs passes every check here. The round-trip half of
 *     `tests/scales.spec.ts` is what proves `toHuman(toStored(h)) === h`.
 *   - Check E's ceilings are loose on purpose, so the band between "plausible" and "correct" is
 *     wide and nothing in it is seen. `900 Hz` is under the hertz ceiling and is not a rate this
 *     schema can express.
 *   - It proves nothing about what is written to the file. `tests/compat.spec.ts` does that, byte
 *     for byte against the real CLI.
 *   - It reads the defaults and the chip values, not every rung of every ladder, and it never
 *     types into a box. A rung reachable only by dragging is not visited.
 *   - It looks at the Study step only. The sign and files steps show the canonical JSON, where
 *     every value is in the schema's own unit and belongs there: that is the file, not a control.
 *   - It measures controls — anything inside a `[data-testid^="field-"]` that contains a `.range`.
 *     Prose, notes, the storage estimate strip and the validity window are not controls and are not
 *     inspected. A storage-unit number in a sentence would not be found.
 *   - It reads text. A number spoken correctly and *converted* wrongly, or one that is right on
 *     screen and wrong in the draft, is invisible to it.
 *
 * A green run means: no control on the Study step is showing a number in the file's unit, in
 * either language, at any value the page offers as a chip. It means nothing more than that.
 *
 * Every check was confirmed to fail on a deliberately broken build before this file was committed.
 * Reverting the `sampling_period_us` adapter to raw microseconds — box, chips and all — trips E on
 * `100000 Hz`. Rendering the raw value instead of the humanised one in `RangeField`'s readout trips
 * A, C and D at once: `300000` with no unit, `300000` against the control's own `5 min` chip, and
 * `300000` on a control that has no box. Dropping the unit from the number box's accessible name
 * trips A. Stopping `units.hours` from rolling into days trips E on `72 h`.
 * ---------------------------------------------------------------------------------------------
 */

import { chromium } from 'playwright';
import { BOUNDS } from '../src/lib/adc/types.ts';
import { en } from '../src/lib/i18n/en.ts';
import { zhTW } from '../src/lib/i18n/zh-TW.ts';

const ORIGIN = process.env.ORIGIN ?? 'http://localhost:4173';

const LOCALES = [
  { id: 'en', browser: 'en-US', catalogue: en },
  { id: 'zh-TW', browser: 'zh-TW', catalogue: zhTW }
];

/**
 * Check D's ceiling. Not a tuning knob: it is the smallest number none of this page's humanisers
 * can produce. Bytes carry at most 1023 of the next unit down before the prefix moves, time
 * converts at 60 and at 24, and `duration_hours` tops out at a year, so 365 is the largest day
 * count. Raising it would blind the check; lowering it would flag `768 MiB`.
 */
const COARSE_CEILING = 1024;

/**
 * Check E's table: the largest number that can honestly stand beside each unit word, and where the
 * ceiling comes from. Two of them are the schema's, four are the humaniser's own conversion
 * points, and two words are forbidden outright.
 *
 * A ceiling is deliberately loose. It is not asserting that a value is right — that is
 * `tests/scales.spec.ts` — only that a number this far out of scale for its own word is not a
 * quantity anybody stated, and is therefore the file's number wearing a person's word.
 */
function ceilings(m, locale) {
  // `Intl`'s own word, in the reader's language, which is where `units.ts` gets days from too.
  // Asked twice, because English inflects and `1 day` and `3 days` are both on screen.
  const dayWord = (count) =>
    new Intl.NumberFormat(locale, { style: 'unit', unit: 'day', unitDisplay: 'short' })
      .format(count)
      .replace(/[\d\s]/g, '');
  const day = dayWord(3);
  const oneDay = dayWord(1);

  const table = new Map([
    // The schema's fastest sensor period is 5000 µs, so 200 Hz, and the keyboard's own ceiling is
    // `BOUNDS.trajectorySamplingHz`. A thousand is loose and still three orders below a µs count.
    [m.unit.hertz, Math.max(1_000, BOUNDS.trajectorySamplingHz[1], 1_000_000 / BOUNDS.samplingPeriodUs[0])],
    // An hour. Past it nobody counts in seconds, and a microsecond count is a million times bigger.
    [m.unit.seconds, 3_600],
    [m.unit.minutes, 1_440], // a day
    [m.unit.hours, 24], // the humanisers roll into days here
    [day, 366], // a year is the longest study `BOUNDS.durationHours` allows
    [oneDay, 366],
    [m.unit.metres, BOUNDS.minimumDisplacementMillimeters[1] / 1_000],
    [m.unit.millimetres, BOUNDS.changeThresholdMillimeters[1]],
    [m.unit.lux, BOUNDS.changeThresholdMillilux[1] / 1_000],
    // `binaryBytes` moves to the next prefix at 1024, so no prefix ever carries more.
    ['B', 1_024],
    ['KiB', 1_024],
    ['MiB', 1_024],
    ['GiB', 1_024]
  ]);

  // The file's units. No control speaks in them, whatever the number is.
  const forbidden = new Set([m.unit.microseconds, m.unit.milliseconds]);

  return { table, forbidden };
}

/* ---------------------------------------------------------------------------------------------
 * Reading numbers out of rendered text.
 *
 * Grouped digits are matched as one token, so `1 073 741 824` is one number and not four. The
 * separators are the ones this site can emit: a plain space, a thin space, a narrow no-break space
 * from `Intl`, and a comma. A single space before a *word* is not a separator, which is what keeps
 * `1 day` at one and `14 days` at fourteen.
 * ------------------------------------------------------------------------------------------- */

const NUMBER = /\d{1,3}(?:[   ,]\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?/g;

function numbersIn(text) {
  if (!text) return [];
  return [...String(text).matchAll(NUMBER)].map((match) =>
    Number(match[0].replace(/[   ,]/g, ''))
  );
}

/** A unit word is any letter — Latin `Hz`, `sec`, `MiB`, or CJK `天`. Digits and punctuation are not. */
function hasUnitWord(text) {
  return /\p{L}/u.test(String(text ?? ''));
}

/**
 * Every number in `text` with the word that follows it: `1 day 12 h` is two pairs, `30 sec – 1 min`
 * is two pairs, `≈ 112 MiB` is one. The word is the run of non-space non-digit characters after the
 * number, which is what a unit is on this site in both languages.
 */
const PAIR = /(\d{1,3}(?:[   ,]\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)\s*([^\s\d]*)/g;

function pairsIn(text) {
  if (!text) return [];
  return [...String(text).matchAll(PAIR)].map((match) => ({
    value: Number(match[1].replace(/[   ,]/g, '')),
    word: match[2]
  }));
}

/** `days` is `day`; nothing else on this site inflects. */
function singular(word) {
  return word.length > 1 && word.endsWith('s') ? word.slice(0, -1) : word;
}

/* ---------------------------------------------------------------------------------------------
 * The scan, run inside the page. Returns one record per control; every judgement is made outside,
 * so a failure can be printed with everything that produced it.
 * ------------------------------------------------------------------------------------------- */

function scanControls() {
  const text = (el) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

  const visible = (el) => {
    if (!el) return false;
    const rect = el.getBoundingClientRect();
    return rect.width > 1 && rect.height > 1;
  };

  const controls = [];
  for (const field of document.querySelectorAll('[data-testid^="field-"]')) {
    const range = field.querySelector('.range');
    if (!range || !visible(field)) continue;

    const box = field.querySelector('input[type="number"]');
    const affix = field.querySelector('.range__affix');

    controls.push({
      path: field.getAttribute('data-testid').slice('field-'.length),
      label: text(field.querySelector('.field__label')),
      box: box
        ? {
            value: box.value,
            min: box.min,
            max: box.max,
            ariaLabel: box.getAttribute('aria-label') ?? '',
            affix: text(affix)
          }
        : null,
      // For a boxed control this is the affix and the echo; for a laddered one it is the humanised
      // value; for the location pair it is both ends. The box's own value is not text and is above.
      readout: text(field.querySelector('.range__readout')),
      valuetexts: [...field.querySelectorAll('input[type="range"]')]
        .map((slider) => slider.getAttribute('aria-valuetext'))
        .filter((value) => value !== null),
      presets: [...field.querySelectorAll('.range__preset')].map((button) => {
        const testid = button.getAttribute('data-testid') ?? '';
        return { stored: testid.slice(testid.lastIndexOf('-') + 1), label: text(button) };
      })
    });
  }
  return controls;
}

/* ---------------------------------------------------------------------------------------------
 * The judgements.
 * ------------------------------------------------------------------------------------------- */

/** Every place a control puts a number, as `{ where, text, unit }`. `unit` is the word beside it. */
function slotsOf(control) {
  const slots = [];
  if (control.box) {
    slots.push({ where: 'number box', text: control.box.value, unit: control.box.affix });
  }
  // The readout of a boxed control is the affix alone and carries no number; of a laddered one it
  // is the humanised value; of the location pair it is `30 sec – 1 min`. All three are one slot.
  slots.push({ where: 'readout', text: control.readout, unit: control.readout });
  for (const valuetext of control.valuetexts) {
    slots.push({ where: 'aria-valuetext', text: valuetext, unit: valuetext });
  }
  for (const preset of control.presets) {
    slots.push({ where: `chip ${JSON.stringify(preset.label)}`, text: preset.label, unit: preset.label });
  }
  return slots;
}

function check(control, locale, scale) {
  const found = [];
  const slots = slotsOf(control);
  const at = (where, message) =>
    found.push({ locale, path: control.path, label: control.label, where, message });

  /* A — every number is spoken with a unit. */
  for (const slot of slots) {
    const numbers = numbersIn(slot.text);
    if (!numbers.length) continue;
    if (numbers.length === 1 && numbers[0] === 0 && !/[1-9]/.test(slot.text)) continue;
    if (!hasUnitWord(slot.unit)) {
      at(slot.where, `shows ${JSON.stringify(String(slot.text).trim())} with no word for its unit`);
    }
  }
  if (control.box) {
    if (!hasUnitWord(control.box.affix)) {
      at('number box', 'has no visible unit beside it');
    } else if (!control.box.ariaLabel.includes(control.box.affix)) {
      at(
        'number box',
        `accessible name ${JSON.stringify(control.box.ariaLabel)} does not name the unit ` +
          `${JSON.stringify(control.box.affix)}, so the unit is sighted-only`
      );
    }
  }

  /* B — nothing outside the control space the box itself declares. */
  if (control.box) {
    const min = Number(control.box.min);
    const max = Number(control.box.max);
    if (Number.isFinite(min) && Number.isFinite(max)) {
      for (const slot of slots) {
        for (const value of numbersIn(slot.text)) {
          if (value < min - 1e-9 || value > max + 1e-9) {
            at(
              slot.where,
              `shows ${value}, outside the ${min}–${max} the box declares — a number that size ` +
                `is only a number in the unit the file stores`
            );
          }
        }
      }
    }
  }

  /* C — no value the control's own chips prove it humanises. Box-less controls only: there the
     chip's testid is a stored value, because those adapters are the identity. On a boxed control
     the testid is already in control space and the comparison would mean nothing. */
  if (!control.box) {
    const humanised = new Map();
    for (const preset of control.presets) {
      const stored = Number(preset.stored);
      if (!Number.isFinite(stored)) continue;
      // The chip shows the stored number itself, so on this control that number reads as itself.
      if (numbersIn(preset.label).includes(stored)) continue;
      humanised.set(stored, preset.label);
    }
    for (const slot of slots) {
      for (const value of numbersIn(slot.text)) {
        const label = humanised.get(value);
        if (label !== undefined) {
          at(
            slot.where,
            `shows ${value}, which this control renders as ${JSON.stringify(label)} on its own ` +
              `chip — the stored form and the humanised form of one value on one control`
          );
        }
      }
    }
  }

  /* D — a box-less control renders humaniser output only, and that cannot reach 1024. */
  if (!control.box) {
    for (const slot of slots) {
      for (const value of numbersIn(slot.text)) {
        if (value >= COARSE_CEILING) {
          at(
            slot.where,
            `shows ${value}; this control has no box, so everything on it comes from a humaniser, ` +
              `and no humaniser on this page can reach ${COARSE_CEILING}`
          );
        }
      }
    }
  }

  /* E — the number has to be plausible for the word beside it. The one check that knows the
     schema, and so the one that still speaks when an adapter is removed and the box goes back to
     declaring the storage range. */
  const spoken = slots.flatMap((slot) =>
    pairsIn(slot.text).map((pair) => ({ ...pair, where: slot.where }))
  );
  if (control.box) {
    spoken.push({ value: Number(control.box.value), word: control.box.affix, where: 'number box' });
  }
  for (const { value, word, where } of spoken) {
    if (!word || !Number.isFinite(value)) continue;
    if (scale.forbidden.has(word)) {
      at(
        where,
        `says ${value} ${word}; ${JSON.stringify(word)} is the unit the file stores in, and no ` +
          `control on this page states a value in it`
      );
      continue;
    }
    const ceiling = scale.table.get(word) ?? scale.table.get(singular(word));
    if (ceiling !== undefined && value > ceiling) {
      at(
        where,
        `says ${value} ${word}, and nothing is ${value} ${word} — the ceiling for that word is ` +
          `${ceiling}, so this is the file's number wearing a person's unit`
      );
    }
  }

  return found;
}

/* ---------------------------------------------------------------------------------------------
 * Driving the browser.
 * ------------------------------------------------------------------------------------------- */

/** Everything the Study step hides behind a switch, opened, so every control is mounted. */
async function openEverything(page) {
  await page.locator('[data-testid="rail-study"]').click();
  await page.waitForSelector('[data-testid="step-study"]');

  const switches = page.locator('[data-testid^="collector-enable-"]');
  const count = await switches.count();
  if (count === 0) throw new Error('no collector switches on the Study step');
  for (let i = 0; i < count; i += 1) {
    const one = switches.nth(i);
    if ((await one.getAttribute('aria-checked')) === 'false') await one.click();
  }
  for (let i = 0; i < count; i += 1) {
    const state = await switches.nth(i).getAttribute('aria-checked');
    if (state !== 'true') throw new Error(`collector ${i} would not switch on`);
  }

  await page.locator('[data-testid="intervention-add"]').click();
  const delivery = page.locator('#delivery [role="switch"]').first();
  if ((await delivery.getAttribute('aria-checked')) === 'false') await delivery.click();
  await page.waitForTimeout(400);
}

const browser = await chromium.launch();
const violations = [];
const pageErrors = [];
let controlsSeen = 0;
let statesSeen = 0;

for (const locale of LOCALES) {
  const context = await browser.newContext({ locale: locale.browser });
  // `app.html` reads this before first paint and `i18n.svelte.ts` after it, so setting it makes the
  // catalogue in force a fact rather than a guess about `navigator.languages`.
  await context.addInitScript((value) => window.localStorage.setItem('adc.locale', value), locale.id);

  const page = await context.newPage();
  await page.setViewportSize({ width: 1440, height: 1400 });
  page.on('pageerror', (error) => pageErrors.push(`${locale.id}: ${error}`));

  await page.goto(`${ORIGIN}/researcher/`, { waitUntil: 'networkidle' });
  await page.waitForTimeout(250);
  await openEverything(page);

  const scale = ceilings(locale.catalogue, locale.id);

  // The page as it opens.
  const controls = await page.evaluate(scanControls);
  if (!controls.length) throw new Error('no controls found on the Study step');
  controlsSeen = controls.length;
  for (const control of controls) {
    statesSeen += 1;
    violations.push(...check(control, locale.id, scale));
  }

  // Then every value the page advertises. A chip is the page saying "this is a value somebody
  // picks"; if picking it puts a stored number on screen, that is the same defect one state later.
  for (const control of controls) {
    for (const preset of control.presets) {
      // A schema path has no quotes and no brackets, so it needs no escaping inside the selector.
      const chip = page.locator(`[data-testid="preset-${control.path}-${preset.stored}"]`);
      if (!(await chip.count())) continue;
      await chip.first().click();
      await page.waitForTimeout(60);
      const refreshed = (await page.evaluate(scanControls)).find((one) => one.path === control.path);
      if (!refreshed) throw new Error(`${control.path} vanished after picking ${preset.label}`);
      statesSeen += 1;
      violations.push(...check(refreshed, locale.id, scale));
    }
  }

  await context.close();
}

await browser.close();

/* ---------------------------------------------------------------------------------------------
 * The report.
 * ------------------------------------------------------------------------------------------- */

const bar = '-'.repeat(96);

/** One control and one kind of complaint is one entry, however many states produced it. */
const grouped = new Map();
for (const violation of violations) {
  const key = `${violation.locale} ${violation.path} ${violation.where} ${violation.message}`;
  if (!grouped.has(key)) grouped.set(key, violation);
}

console.log(`\n${bar}\nSTORAGE-UNIT NUMBERS ON /researcher/ — these fail the build\n${bar}`);
if (grouped.size === 0) {
  console.log('  nothing');
} else {
  for (const violation of grouped.values()) {
    console.log(`\n${violation.path}   [${violation.locale}]`);
    console.log(`  control     ${JSON.stringify(violation.label)}`);
    console.log(`  where       ${violation.where}`);
    console.log(`  problem     ${violation.message}`);
  }
}

console.log(`\n${bar}`);
console.log(
  `${LOCALES.length} locales × ${controlsSeen} controls, ${statesSeen} control states inspected`
);
console.log(`  ${grouped.size} controls showing a number in the unit the file stores`);

if (pageErrors.length) {
  console.log(`\nthe page logged errors:\n  ${pageErrors.join('\n  ')}`);
}

if (grouped.size) {
  console.error(
    `\nFAIL — ${grouped.size} control${grouped.size === 1 ? '' : 's'} put a storage-unit number on screen.`
  );
  process.exit(1);
}
console.log('\nPASS — every number on every control is one a person would say.');
