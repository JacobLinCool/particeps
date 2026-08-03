/**
 * The locale identifiers and the shape both catalogues satisfy.
 *
 * Two rules decide what may live in a catalogue. The interface is meant to be operable without
 * reading, so a string exists only where structure, state, or an icon cannot carry the meaning —
 * form labels, accessible names for icon-only controls, the rules a value has to satisfy, and
 * almost nothing else. And nothing a researcher writes is ever translated: `title`, `purpose`,
 * `researcher`, and `consent.summary` are signed bytes that render exactly as they were signed, so
 * no message here interpolates study text.
 *
 * `researcher.how` and `participant.how` are the one place prose is allowed. They are the same
 * flow told to the two audiences who need different halves of it.
 */

import type { CollectorId } from '$lib/adc/types';

export const LOCALES = ['en', 'zh-TW'] as const;

export type Locale = (typeof LOCALES)[number];

/** What a reader chose. `system` defers to the browser, and is the state nothing has been chosen. */
export const LOCALE_PREFERENCES = ['system', ...LOCALES] as const;

export type LocalePreference = (typeof LOCALE_PREFERENCES)[number];

/** A titled paragraph. Used only by the two narratives. */
export interface Passage {
  title: string;
  body: string;
}

/**
 * A form section that needs nothing said about it. The heading and the controls under it carry the
 * meaning, which is the ordinary case: a note repeating what the fields already show is one more
 * line to read and one more line to wrap.
 */
export interface SectionTitle {
  title: string;
}

/**
 * A section whose note states something the controls cannot: what happens in a state they are not
 * currently in. Declared apart from `SectionTitle` rather than as an optional field, because an
 * optional field is a thing that comes back.
 */
export interface Section extends SectionTitle {
  note: string;
}

export interface CollectorCopy {
  /** The app's own participant-facing name, kept identical so a researcher reads what a
   *  participant will read. Changing one without the other makes the two disagree. */
  name: string;
  records: string;
  limit: string;
}

/**
 * Keyed by the `code` on `Issue`, so the UI can look a problem up directly. These are the wire
 * identifiers rather than English, which is why they are the only snake_case keys in the file.
 */
export interface IssueMessages {
  required: string;
  id_format: string;
  length_range: (bounds: { min: number; max: number }) => string;
  number_range: (bounds: { min: number; max: number }) => string;
  integer: string;
  instant: string;
  window_order: string;
  collectors_empty: string;
  duplicate_id: string;
  transports_empty: string;
  location_interval_order: string;
  endpoint_scheme: string;
  endpoint_host: string;
  document_too_large: (bounds: { max: number }) => string;
  signer_missing: string;
  export_key_missing: string;
  keyset_unusable: string;
  language_tag: string;
  unknown_reference: string;
  selection_bounds: string;
  schedule_bounds: string;
}

export type IssueCode = keyof IssueMessages;

export interface Messages {
  app: {
    /** A product name, not prose: the same in every locale. */
    name: string;
    tagline: string;
    nav: { researcher: string; participant: string };
  };

  language: {
    label: string;
    system: string;
    /** Endonyms, so a reader can find their language without reading the one on screen. */
    en: string;
    zhTW: string;
  };

  action: {
    generate: string;
    sign: string;
    download: string;
    copy: string;
    importDraft: string;
    back: string;
    next: string;
    /** The reader asserting a downloaded private key reached their disk. */
    confirmSaved: string;
    /** The skip link. Visible only under the keyboard, which is who it is for. */
    skip: string;
    startOver: string;
    confirm: string;
    cancel: string;
  };

  intervention: {
    title: string;
    /** One of them. `title` and the section heading are both plural and cannot name a single card. */
    one: string;
    empty: string;
    notificationTiming: string;
    anonymous: string;
    personalized: string;
    assignedId: string;
    addNotification: string;
    addSurvey: string;
    addQuestion: string;
    addTrigger: string;
    survey: string;
    surveyTitle: string;
    surveyDescription: string;
    question: string;
    questionType: string;
    prompt: string;
    required: string;
    maximumLength: string;
    scaleBounds: string;
    endpointLabels: string;
    options: string;
    selectionBounds: string;
    notificationTitle: string;
    notificationMessage: string;
    trigger: string;
    scheduleType: string;
    clock: string;
    offset: string;
    interval: string;
    localTime: string;
    availability: string;
    types: { shortText: string; scale: string; singleChoice: string; multipleChoice: string };
    schedules: { oneTime: string; interval: string; dailyLocal: string };
    clocks: { calendar: string; active: string };
  };

  /** Accessible names for controls that carry no text. Visible verbs live in `action`. */
  control: {
    language: string;
    details: string;
    remove: string;
    reveal: string;
    conceal: string;
    progress: string;
    /** The printer. `researcher.sign.publish` is why you would; this is what the button does. */
    print: string;
    /** The fingerprint plaque. The value is the button's content, so the name is the act. */
    copyFingerprint: string;
    /** The derived slug offered beside an ID field. */
    applySuggestion: string;
    /** The zone selector beside an instant. */
    timezone: string;
    stepPosition: (position: { index: number; total: number }) => string;
  };

  step: {
    keys: string;
    study: string;
    sign: string;
    files: string;
  };

  /**
   * The word beside a number. The label says what the number is; this says what it is in — and a
   * researcher is never shown or asked for a number without it, so these are the units a person
   * states a value in rather than the ones the file happens to store.
   */
  unit: {
    microseconds: string;
    milliseconds: string;
    seconds: string;
    minutes: string;
    hours: string;
    hertz: string;
    metres: string;
    mebibytes: string;
    bytes: string;
  };

  /** File names are identifiers, not prose, and are the same in both locales. */
  file: {
    signingPrivate: string;
    signingPublic: string;
    exportPrivate: string;
    exportPublic: string;
    canonical: string;
    signed: string;
  };

  field: {
    label: {
      experimentId: string;
      configurationId: string;
      issuedAt: string;
      expiresAt: string;
      title: string;
      researcherName: string;
      researcherContact: string;
      purpose: string;
      durationHours: string;
      consentDocumentVersion: string;
      consentSummary: string;
      storageQuota: string;
      required: string;
      samplingPeriod: string;
      reportLatency: string;
      bandwidthEstimates: string;
      transports: string;
      pollInterval: string;
      interval: string;
      fastestInterval: string;
      batchDelay: string;
      displacement: string;
      priority: string;
      trajectoryRate: string;
      upload: string;
      endpoint: string;
      uploadInterval: string;
      allowMetered: string;
      signerKeyId: string;
      signerPublicKey: string;
      exportKeyId: string;
      exportKeyset: string;
      fingerprint: string;
    };
    /**
     * No hint may state or imply a power, battery, or energy cost: nothing here has been measured,
     * and a hint is not the place to guess. Bytes and hours are measured, and the quota meter says
     * those.
     */
    hint: {
      /**
       * The one hint the three override fields share. Every identifier on the page is derived, so
       * the only thing any of the three has to say is what an empty field means — and saying it
       * once keeps the disclosure from repeating itself three times in three slightly different
       * sentences.
       */
      override: string;
      contact: string;
      expiresAt: string;
      duration: string;
      consentSummary: string;
      storageQuota: string;
      required: string;
      samplingPeriod: string;
      bandwidthEstimates: string;
      pollInterval: string;
      fastestInterval: string;
      batchDelay: string;
      priority: string;
      endpoint: string;
      allowMetered: string;
    };
  };

  option: {
    transport: { wifi: string; mobile: string };
    priority: { balanced: string; highAccuracy: string };
  };

  collector: Record<CollectorId, CollectorCopy>;

  issue: IssueMessages;

  status: {
    copied: string;
    verified: string;
    stale: string;
    clean: string;
  };

  empty: {
    files: string;
  };

  error: {
    insecureContext: string;
    unsupportedBrowser: string;
    signing: string;
    draft: string;
    keyFile: string;
    clipboard: string;
    /** The static build ships a 404 fallback, so the one route nobody designed still needs words. */
    notFound: string;
  };

  confirm: {
    startOver: { title: string; body: string };
    leave: { title: string };
    replaceKey: { title: string; body: string };
  };

  researcher: {
    title: string;
    lede: string;
    how: {
      file: Passage;
      keys: Passage;
      local: Passage;
      fingerprint: Passage;
      disclosure: Passage;
    };
    /**
     * No `role` on either key any more. The step generates both on arrival and shows them as two
     * files, so what each key is for is carried by the pair of icons and by the one orientation
     * line in `how.keys`; the only sentence left per key is the one that differs — what its loss
     * costs. `reuse` and `reuseNote` label the disclosure that hides the import path, which is the
     * rare case rather than the offered one.
     */
    keys: {
      signing: { title: string; algorithm: string; risk: string };
      export: { title: string; algorithm: string; risk: string };
      handling: string;
      reuse: string;
      reuseNote: string;
    };
    study: {
      /**
       * Seven, not eight. Neither identifier is typed here any more — both are derived and read out
       * on the sign step — and the quota sits with the collectors that fill it.
       */
      section: {
        about: SectionTitle;
        validity: SectionTitle;
        /** `assigned_participant_id`, which is a root key about the whole study: it decides whether
         *  one signed file goes to everyone or each participant gets their own. */
        identity: SectionTitle;
        collectors: SectionTitle;
        consent: SectionTitle;
        interventions: SectionTitle;
        /** The one note left: it says what happens with the toggle off, which is the toggle's
         *  meaning and not advice about filling the section in. */
        delivery: Section;
      };
      /** One line each, beside the control the sentence is about, not in the section heading. */
      note: {
        irrevocable: string;
        /**
         * What marking a source Required costs a participant. Said once above the seven cards
         * rather than seven times inside them: the pill on each card says *this one is marked*,
         * and this says what being marked does. Every pill names it with `aria-describedby`, so
         * one sentence is still the description of all seven controls that cause it.
         */
        required: string;
        disclosure: string;
        delivery: string;
      };
    };
    sign: {
      /** What the study is about to be called. Read out here, because this is where it becomes real. */
      identity: Section;
      canonical: string;
      size: (extent: { bytes: number; max: number }) => string;
      blocked: (count: number) => string;
      publish: string;
    };
    files: {
      keep: string;
      /** Why the canonical JSON is not a nicety. Nothing else on the page states the dependency. */
      archive: string;
      publish: string;
      distribute: string;
      pilot: string;
    };
    cli: string;
  };

  participant: {
    title: string;
    lede: string;
    how: {
      start: Passage;
      list: Passage;
      storage: Passage;
      fingerprint: Passage;
      control: Passage;
    };
    /** The five setup screens, named as the app names them so the page and the phone agree. */
    flow: {
      study: Passage;
      data: Passage;
      consent: Passage;
      access: Passage;
      start: Passage;
    };
    note: string;
    closing: string;
  };

  link: {
    researcherGuide: string;
    participantGuide: string;
    threatModel: string;
    source: string;
  };
}
