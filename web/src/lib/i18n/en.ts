/**
 * English. Also the fallback: any browser not asking for Traditional Chinese lands here.
 */

import type { Messages } from './types';

const number = new Intl.NumberFormat('en-US');

export const en: Messages = {
  app: {
    name: 'Particeps',
    tagline: 'Run a study without building an app.',
    nav: { researcher: 'Researcher', participant: 'Participant' }
  },

  language: {
    label: 'Language',
    system: 'Browser default',
    en: 'English',
    zhTW: '正體中文'
  },

  action: {
    generate: 'Generate',
    sign: 'Sign',
    download: 'Download',
    copy: 'Copy',
    importDraft: 'Import JSON',
    open: 'Open',
    back: 'Back',
    next: 'Next',
    confirmSaved: 'I have the file',
    skip: 'Skip to content',
    startOver: 'Start over',
    confirm: 'Confirm',
    cancel: 'Cancel'
  },

  intervention: {
    title: 'Interventions and surveys',
    one: 'Intervention',
    empty: 'No scheduled activities.',
    notificationTiming: 'Android notification timing is best effort, not an exact wall-clock instant.',
    anonymous: 'Anonymous / pseudonymous',
    personalized: 'Personalized',
    assignedId: 'Assigned participant code',
    addNotification: 'Add notification',
    addSurvey: 'Add survey',
    addQuestion: 'Add question',
    addTrigger: 'Add schedule',
    addWindow: 'Add time window',
    survey: 'Survey',
    surveyTitle: 'Survey title',
    surveyDescription: 'Survey description',
    question: 'Question ID',
    questionType: 'Question type',
    prompt: 'Question text',
    required: 'Required',
    maximumLength: 'Maximum characters',
    scaleBounds: 'Scale minimum / maximum',
    endpointLabels: 'Endpoint labels',
    options: 'Options (stable ID | label, one per line)',
    selectionBounds: 'Minimum / maximum selections',
    notificationTitle: 'Notification title',
    notificationMessage: 'Notification message',
    trigger: 'Schedule ID',
    scheduleType: 'Schedule type',
    clock: 'Relative clock',
    offset: 'Offset in minutes',
    interval: 'Interval in minutes',
    localTime: 'Local time',
    windowStart: 'Window starts',
    windowEnd: 'Window ends',
    occurrencesPerWindow: 'Prompts per window',
    dailyMaximum: 'Maximum prompts per day',
    totalMaximum: 'Maximum prompts in study',
    minimumSeparation: 'Minimum separation in minutes',
    randomWindowSummary: ({ minimum, maximum }) =>
      `${number.format(minimum)}–${number.format(maximum)} possible prompts; exact times are chosen and stored on the phone.`,
    availability: 'Available for minutes',
    types: { shortText: 'Short text', scale: 'Numeric scale', singleChoice: 'Single choice', multipleChoice: 'Multiple choice' },
    schedules: {
      oneTime: 'One time',
      interval: 'Recurring interval',
      dailyLocal: 'Daily local time',
      randomWindow: 'Random local windows'
    },
    clocks: { calendar: 'Calendar time (pauses included)', active: 'Running time (pauses excluded)' }
  },

  control: {
    language: 'Language',
    details: 'Details',
    remove: 'Remove',
    reveal: 'Show',
    conceal: 'Hide',
    progress: 'Progress',
    print: 'Print the fingerprint',
    copyFingerprint: 'Copy the fingerprint',
    applySuggestion: 'Use this ID',
    timezone: 'Time zone',
    stepPosition: ({ index, total }) => `Step ${index} of ${total}`
  },

  step: {
    keys: 'Keys',
    study: 'Study',
    sign: 'Sign',
    files: 'Files',
    read: 'Read'
  },

  unit: {
    microseconds: 'µs',
    milliseconds: 'ms',
    // Byte-identical to what `Intl.NumberFormat` short unit style emits, because the word beside
    // the box and the word in the readout have to be the same word for the same unit.
    seconds: 'sec',
    minutes: 'min',
    hours: 'h',
    hertz: 'Hz',
    metres: 'm',
    millimetres: 'mm',
    lux: 'lux',
    mebibytes: 'MiB',
    bytes: 'bytes'
  },

  file: {
    signingPrivate: 'study-signing-private.key',
    signingPublic: 'study-signing-public.key',
    exportPrivate: 'export-hpke-private.key',
    exportPublic: 'export-hpke-public.json',
    canonical: 'study-canonical.json',
    signed: 'study.partcfg'
  },

  field: {
    label: {
      experimentId: 'Experiment ID',
      configurationId: 'Configuration ID',
      issuedAt: 'Starts',
      expiresAt: 'Ends',
      title: 'Title',
      researcherName: 'Researcher',
      researcherContact: 'Contact',
      purpose: 'Purpose',
      durationHours: 'Each person',
      consentDocumentVersion: 'Consent document version',
      consentSummary: 'Consent summary',
      storageQuota: 'Space it may use',
      required: 'Required',
      samplingPeriod: 'Sampling period',
      reportLatency: 'Maximum report latency',
      bandwidthEstimates: 'Bandwidth estimates',
      transports: 'Transports',
      pollInterval: 'Poll interval',
      interval: 'Interval',
      fastestInterval: 'Fastest update',
      batchDelay: 'Batch delay',
      displacement: 'Minimum displacement',
      priority: 'Priority',
      trajectoryRate: 'Trajectory sampling',
      changeThreshold: 'Change threshold',
      minimumEventInterval: 'Minimum event interval',
      upload: 'Scheduled upload',
      endpoint: 'Endpoint',
      uploadInterval: 'Interval',
      allowMetered: 'Allow mobile data',
      signerKeyId: 'Signing key ID',
      signerPublicKey: 'Signing public key',
      exportKeyId: 'Export key ID',
      exportPublicKey: 'Export public key',
      fingerprint: 'Fingerprint'
    },
    /**
     * Nothing here may state or imply a power, battery, or energy cost. The project has not
     * measured one, and a hint is not the place to guess. Bytes and hours are measured, and the
     * quota meter says those.
     */
    hint: {
      override: 'Leave empty to use the ID above.',
      contact: 'A way a participant can actually reach you.',
      expiresAt: 'The last day anyone can join.',
      duration: 'Counted from the day they start.',
      consentSummary:
        'Data, purpose, duration, risks, access, export, withdrawal, deletion, contact.',
      storageQuota: 'When it fills, collection stops. Nothing is dropped.',
      required: 'The study cannot start without this access.',
      samplingPeriod: 'A request, not a limit. Devices may go faster.',
      ambientLightSamplingPeriod:
        'A hard minimum between emitted events. The latest meaningful change is retained for the next eligible emission.',
      bandwidthEstimates: 'Platform estimates, not measurements.',
      pollInterval: 'A minute is a pilot setting, not a study setting.',
      fastestInterval: 'Never longer than the interval.',
      batchDelay: 'Higher means fewer, larger deliveries.',
      priority: 'Both need precise location. High accuracy uses GPS.',
      endpoint: 'https, and yours to run.',
      allowMetered: 'Off means Wi-Fi only. Mobile data is a cost nobody agreed to.'
    }
  },

  option: {
    transport: { wifi: 'Wi-Fi', mobile: 'Mobile data' },
    priority: { balanced: 'Balanced', highAccuracy: 'High accuracy' }
  },

  collector: {
    'app_lifecycle.v1': {
      name: 'App activity',
      records: 'When this app’s own screens open and close',
      limit: 'Nothing about any other app'
    },
    'accelerometer.v1': {
      name: 'Motion',
      records: 'Raw x/y/z acceleration, gravity included',
      limit: 'No activity, posture, or gesture labels'
    },
    'battery_state.v1': {
      name: 'Battery state',
      records: 'Percentage, charging state and source, and power-save state',
      limit: 'No serial, hardware ID, health, or temperature'
    },
    'temporal_context.v1': {
      name: 'Time context',
      records: 'Time-zone ID, UTC offset, DST state, and clock-change reason',
      limit: 'A time zone is not treated as a location or travel record'
    },
    'gyroscope.v1': {
      name: 'Rotation',
      records: 'Raw x/y/z angular velocity and sensor accuracy',
      limit: 'No orientation, activity, posture, or gesture inference'
    },
    'ambient_light.v1': {
      name: 'Ambient light',
      records: 'Raw illuminance, sensor time, and accuracy',
      limit: 'No image, environmental content, or presence inference'
    },
    'proximity.v1': {
      name: 'Proximity',
      records: 'Raw distance, maximum range, and near/far interpretation',
      limit: 'Many phones report only near/far; values are not comparable across devices'
    },
    'network_state.v1': {
      name: 'Connection type',
      records: 'Transport, metered, roaming, and validation',
      limit: 'No SSID, address, destination, or content'
    },
    'network_usage.v1': {
      name: 'Data volume',
      records: 'Device-total bytes and packets, per transport',
      limit: 'Not per app, and not when the traffic happened'
    },
    'usage_events.v1': {
      name: 'App and screen use',
      records: 'App switches and screen events, by package',
      limit: 'Delayed and incomplete; not a session stream'
    },
    'location.v1': {
      name: 'Location',
      records: 'Fused fixes: accuracy, speed, altitude, bearing',
      limit: 'Sampled estimates with gaps, not a track'
    },
    'keyboard_touch.v1': {
      name: 'Keyboard touch',
      records: 'Within-key position, timing, pressure, and size',
      limit: 'Research keyboard only. No text, no key identity.'
    }
  },

  issue: {
    required: 'Required',
    id_format: 'Lowercase letters, digits, and hyphens; 3–64 characters',
    length_range: ({ min, max }) => `${number.format(min)}–${number.format(max)} characters`,
    number_range: ({ min, max }) => `${number.format(min)} to ${number.format(max)}`,
    integer: 'Whole numbers only',
    instant: 'Not a date and time this can read',
    window_order: 'Must be after the start of the window',
    collectors_empty: 'Enable at least one source',
    duplicate_id: 'Already used in this study',
    transports_empty: 'Choose at least one',
    location_interval_order: 'Cannot exceed the interval',
    endpoint_scheme: 'Must begin with https://',
    endpoint_host: 'No host in that address',
    document_too_large: ({ max }) =>
      `The whole configuration must stay under ${number.format(max)} bytes`,
    signer_missing: 'Generate the signing key first',
    export_key_missing: 'Generate the export key first',
    key_invalid: 'Not a canonical 32-byte Protocol v1 public key. Generate or import the key again',
    language_tag: 'Use a valid BCP 47 language tag',
    unknown_reference: 'Choose a survey defined in this configuration',
    selection_bounds: 'Selection limits do not match this question',
    schedule_bounds: 'This schedule is outside the study or creates too many occurrences'
  },

  status: {
    copied: 'Copied',
    verified: 'Signature verified here',
    stale: 'Changed since signing. Sign again.',
    clean: 'Nothing to fix'
  },

  empty: {
    files: 'Nothing to hand out until the configuration is signed.'
  },

  error: {
    insecureContext: 'Key generation needs a secure context. Open this page over https.',
    unsupportedBrowser: 'This browser cannot generate keys.',
    signing: 'Signing failed. Nothing was written.',
    draft: 'That file is not a study configuration this page can read.',
    keyFile: 'That file is not a private key this page can read.',
    clipboard: 'Copy failed. Select the text and copy it yourself.',
    notFound: 'No page at this address.',
    bundle: {
      not_a_bundle: 'That file is not an export bundle this page can read.',
      too_large: 'That file is larger than this tab can open.',
      wrong_study: 'This bundle is from another study. Use that study\u2019s configuration.',
      wrong_key: 'This private key does not open this study.',
      unwrap_failed: 'This is not the configuration this bundle was sealed under.',
      tag_failed: 'This bundle changed after the phone wrote it.',
      unreadable: 'It decrypted, but the contents are not a shape this page reads.'
    }
  },

  confirm: {
    startOver: {
      title: 'Discard everything in this tab?',
      body: 'The keys and the configuration are held here and nowhere else. Anything you have not downloaded is gone.'
    },
    leave: { title: 'Leave with a key you have not saved?' },
    replaceKey: {
      title: 'Replace the key held here?',
      body: 'The pair in this tab is discarded and a new one takes its place. Anything already signed stays signed under the old key ID.'
    }
  },

  researcher: {
    title: 'Prepare a study',
    lede: 'Keys, study, and signature, in this tab. Nothing leaves it.',
    how: {
      file: {
        title: 'A study is a file',
        body: 'You describe what to collect, sign it with your own key, and hand the file to participants. Any build of the app runs it — there is no app to write and nothing to deploy.'
      },
      keys: {
        title: 'Two keys, two jobs',
        body: 'One key signs the study, one decrypts what comes back.'
      },
      local: {
        title: 'Nothing leaves this tab',
        body: 'Nothing here is backed up. Download the keys before you close the tab.'
      },
      fingerprint: {
        title: 'Publish the fingerprint',
        body: 'A signature proves the file is unchanged since signing, not who wrote it. Put the fingerprint in the material that recruits participants; the consent screen shows them the same eight groups to compare it against.'
      },
      disclosure: {
        title: 'The app describes the data, not you',
        body: 'Before consent, the app describes every source you switched on.'
      }
    },
    /**
     * The step makes both keys on arrival, so nothing here asks for a decision. What is left is two
     * files and one line of red: the two `risk` lines open on the same word in the same slot on the
     * same tile, so the eye compares the second half only, and only the export one is drawn in
     * `--danger`. Neither is a warning about keys in general; between them they say which loss is
     * an afternoon and which one is the data.
     */
    keys: {
      signing: {
        title: 'Study signing key',
        algorithm: 'Ed25519',
        risk: 'Lost: make a new one.'
      },
      export: {
        title: 'Export encryption key',
        algorithm: 'X25519 · HPKE',
        /** The tightest slot on the site: a 135px strip inside a download tile on the files step. */
        risk: 'Lost: data unreadable.'
      },
      /** The hold column's hint on the files step, in 218px. The only leak-side line on the site,
       *  and it sits where the files are being filed rather than where they are being made. */
      handling: 'Not in Git, not in chat.',
      /** Behind a closed disclosure: nobody is asked to bring a key, which is not the same as
       *  nobody being allowed to. `reuseNote` is the only reason anyone would. */
      reuse: 'Use a key I already have',
      reuseNote: 'The same key keeps the same fingerprint.'
    },
    study: {
      /** Five headings and one note. The controls under each heading say what the section is for,
       *  and only `delivery` has something to add that no control on screen shows. */
      section: {
        about: { title: 'The study' },
        validity: { title: 'How long does this run?' },
        identity: { title: 'Who is this file for?' },
        collectors: { title: 'Data' },
        consent: { title: 'Consent' },
        interventions: { title: 'Interventions' },
        delivery: {
          title: 'Delivery',
          note: 'With this off, data only leaves when they export it.'
        }
      },
      /** One line each, beside the control the sentence is about. */
      note: {
        irrevocable: 'A file you have handed out cannot be called back.',
        /** Above the twelve cards once, because the consequence is identical on every card. */
        required: 'Mark a source Required and a participant who declines it cannot start the study.',
        disclosure: 'The app lists the data in its own words. Agree with it.',
        delivery: 'They cannot take part and decline this. Say so in consent.'
      }
    },
    sign: {
      identity: {
        title: 'Identity',
        /** Four rows now, not two: the study, the file, and the two keys. Every one is derived. */
        note: 'These names travel with your data. Analysis needs them.'
      },
      canonical: 'Canonical JSON',
      size: ({ bytes, max }) => `${number.format(bytes)} / ${number.format(max)} bytes`,
      blocked: (count) =>
        count === 1 ? '1 problem to fix' : `${number.format(count)} problems to fix`,
      publish: 'Publish this where you recruit. It is what a participant compares.'
    },
    files: {
      keep: 'Save your keys',
      /** 218px. The reason — no command extracts this from the .partcfg — did not fit and is in the
       *  researcher guide; what fits is the consequence, which is the half that changes a decision. */
      archive: 'Needed to decrypt your own data.',
      publish: 'The fingerprint goes into your recruitment material.',
      distribute: 'For participants',
      pilot: 'Pilot on the phones your study targets before you recruit anyone.',
      join: {
        title: 'Optional join link and QR',
        artifactUrl: 'HTTPS address of the signed .partcfg',
        artifactHint: 'Host the exact signed file first, then enter its final address. Redirects are refused.',
        personalizedHint: 'Use a long opaque final path segment. Do not put the assigned participant ID in the address.',
        copy: 'Copy join link',
        invalid: 'Enter a valid final HTTPS address. Personalized files require a long opaque path that does not reveal the assigned participant ID.',
        immutable: 'The QR is made locally and binds this file’s complete SHA-256 and signing fingerprint. The app downloads it once; it does not poll for changes.',
        qrAlt: 'QR code for the immutable study join link'
      }
    },
    read: {
      lede: 'Open an export a participant sent back. Nothing leaves this tab.',
      bundle: 'Encrypted export',
      session: 'From this tab',
      opened: 'Open in this tab',
      events: 'Events',
      window: 'Sequence numbers',
      span: 'First to last event',
      transitions: 'State changes',
      exported: 'Exported',
      instance: 'Participant instance',
      state: 'State',
      json: 'Decrypted JSON',
      large: 'Too large to show here. Download it instead.',
      none: 'No events in this export.'
    },
    cli: 'researcher-tools does all of this from a terminal, and also decrypts the bundles you get back.'
  },

  participant: {
    title: 'Before you agree',
    lede: 'What the app does on your phone, and what it asks of you, while declining still costs nothing.',
    how: {
      start: {
        title: 'Nothing is collected until you press start',
        body: 'Importing the file your research team gave you collects nothing. Setup is five screens and you can stop on any of them. Declining is a complete answer; you do not owe anyone a reason.'
      },
      list: {
        title: 'You see the list before you agree',
        body: 'One screen names every source the study switched on and the rate it asked for. That text belongs to the app, not to the research team — no setting in their file can soften it.'
      },
      storage: {
        title: 'It stays on your phone',
        body: 'Events are encrypted as they are written. They reach the research team when you export a file and send it yourself — or on a schedule, if the study says so on the consent screen before you agree.'
      },
      fingerprint: {
        title: 'Check the fingerprint',
        body: 'The consent screen shows eight groups of four characters. Your research team should have published the same eight somewhere you already trust; compare them. A signature only proves the file is unaltered, not who wrote it, and this comparison is what closes that gap. If they differ, or nobody gave you one, ask before you agree.'
      },
      control: {
        title: 'You can stop at any time',
        body: 'Pause, finish early, or withdraw. No reason is required and the app does not ask for one. Afterwards you can still export what was collected, and delete all of it from the phone. What has already left the phone can only be dealt with by asking the research team.'
      }
    },
    flow: {
      study: {
        title: 'Study',
        body: 'The title, the team, how to reach them, and how long it runs.'
      },
      data: {
        title: 'Data',
        body: 'Every source it switched on, and how often each one samples.'
      },
      consent: {
        title: 'Consent',
        body: 'The team’s own text, the signature fingerprint, and whether the study sends data on its own.'
      },
      access: {
        title: 'Access',
        body: 'The Android permissions those sources need. Anything marked optional can stay off.'
      },
      start: {
        title: 'Start',
        body: 'Collection begins on this press, and not before.'
      }
    },
    note: 'This page describes the app. Where it and your study’s consent document disagree, the consent document comes first.',
    closing:
      'If what you see on the phone does not match what you were told, stop and ask — using contact details you already had, not ones taken from the study screen.'
  },

  link: {
    researcherGuide: 'Researcher guide',
    participantGuide: 'Participant guide',
    threatModel: 'Threat model',
    source: 'Source'
  }
};
