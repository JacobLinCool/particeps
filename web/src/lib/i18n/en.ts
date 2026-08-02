/**
 * English. Also the fallback: any browser not asking for Traditional Chinese lands here.
 */

import type { Messages } from './types';

const number = new Intl.NumberFormat('en-US');

export const en: Messages = {
  app: {
    name: 'Android Data Collector',
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
    addPrompt: 'Add prompt',
    back: 'Back',
    next: 'Next',
    confirmSaved: 'I have the file',
    skip: 'Skip to content',
    startOver: 'Start over',
    confirm: 'Confirm',
    cancel: 'Cancel'
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
    files: 'Files'
  },

  unit: {
    microseconds: 'µs',
    milliseconds: 'ms',
    minutes: 'min',
    hours: 'h',
    hertz: 'Hz',
    metres: 'm',
    mebibytes: 'MiB',
    bytes: 'bytes'
  },

  file: {
    signingPrivate: 'study-signing-private.key',
    signingPublic: 'study-signing-public.key',
    exportPrivate: 'export-hpke-private.json',
    exportPublic: 'export-hpke-public.json',
    canonical: 'study-canonical.json',
    signed: 'study.adccfg'
  },

  field: {
    label: {
      experimentId: 'Experiment ID',
      configurationId: 'Configuration ID',
      issuedAt: 'Valid from',
      expiresAt: 'Valid until',
      minimumAppVersion: 'Minimum app version',
      title: 'Title',
      researcherName: 'Researcher',
      researcherContact: 'Contact',
      purpose: 'Purpose',
      durationHours: 'Duration',
      consentDocumentVersion: 'Consent document version',
      consentSummary: 'Consent summary',
      storageQuota: 'Local storage limit',
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
      promptId: 'Prompt ID',
      promptDelay: 'Delay',
      promptMessage: 'Message',
      upload: 'Scheduled upload',
      endpoint: 'Endpoint',
      uploadInterval: 'Interval',
      allowMetered: 'Allow mobile data',
      signerKeyId: 'Signing key ID',
      signerPublicKey: 'Signing public key',
      exportKeyId: 'Export key ID',
      exportKeyset: 'Export public keyset',
      fingerprint: 'Fingerprint'
    },
    hint: {
      id: 'Lowercase letters, digits, and hyphens. 3–64 characters.',
      contact: 'Something a participant can actually reach you on.',
      duration: 'Counted from the participant’s first start.',
      consentSummary:
        'Data, purpose, duration, risks, access, export, withdrawal, deletion, what you retain, and how to reach you.',
      minimumAppVersion: 'The app’s versionCode. Below it, the study will not import.',
      required: 'The study cannot start until this access is granted.',
      samplingPeriod: 'A request, not a limit — devices deliver faster than asked.',
      bandwidthEstimates: 'Platform estimates, not measurements.',
      pollInterval: 'A minute is a pilot setting, not a study setting.',
      fastestInterval: 'Never longer than the interval.',
      batchDelay: 'Higher batches more fixes and costs less power.',
      priority: 'Both need precise location. This trades power against accuracy.',
      promptDelay: 'From the first start. Delivery is inexact.',
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
      records: 'Raw x/y/z acceleration in device axes, gravity included',
      limit: 'No activity, posture, or gesture labels'
    },
    'network_state.v1': {
      name: 'Connection type',
      records: 'Transport, validated, metered, roaming, and optional bandwidth estimates',
      limit: 'No SSID, address, destination, or content'
    },
    'network_usage.v1': {
      name: 'Data volume',
      records: 'Device-total bytes and packets per transport, over an explicit window',
      limit: 'Not per app, and not when the traffic happened'
    },
    'usage_events.v1': {
      name: 'App and screen use',
      records: 'Foreground changes, screen, keyguard, and boot events, with package names',
      limit: 'Delayed and incomplete; not a session stream'
    },
    'location.v1': {
      name: 'Location',
      records: 'Fused fixes with accuracy, speed, altitude, and bearing',
      limit: 'Sampled estimates with gaps, not a continuous track'
    },
    'keyboard_touch.v1': {
      name: 'Keyboard touch',
      records:
        'Within-key position, timing, pressure, size, and key category — research keyboard only',
      limit: 'No text, no key identity, no calibrated force'
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
    keyset_unusable: 'Not a keyset the app can encrypt to. Generate or import the export key again'
  },

  status: {
    copied: 'Copied',
    verified: 'Signature verified here',
    stale: 'Changed since signing. Sign again.',
    clean: 'Nothing to fix'
  },

  empty: {
    prompts: 'None. Most studies need none.',
    files: 'Nothing to hand out until the configuration is signed.'
  },

  error: {
    insecureContext: 'Key generation needs a secure context. Open this page over https.',
    unsupportedBrowser: 'This browser cannot generate keys.',
    signing: 'Signing failed. Nothing was written.',
    draft: 'That file is not a study configuration this page can read.',
    keyFile: 'That file is not a private key this page can read.',
    clipboard: 'Copy failed. Select the text and copy it yourself.',
    notFound: 'No page at this address.'
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
    beyond:
      'After the study ends. This prompt will never reach a participant who finishes on time.',
    lede: 'Keys, configuration, and signature, in this tab. Nothing to install; nothing leaves the browser.',
    how: {
      file: {
        title: 'A study is a file',
        body: 'You describe what to collect, sign it with your own key, and hand the file to participants. Any build of the app runs it — there is no app to write and nothing to deploy.'
      },
      keys: {
        title: 'Two keys, two jobs',
        body: 'One signs the study, so a phone can tell the file is unaltered. One decrypts what comes back. Both public halves travel inside the file; both private halves stay with you. Lose the signing key and you cannot issue under that key ID again. Lose the export key and every bundle collected under it is unreadable, permanently.'
      },
      local: {
        title: 'Nothing leaves this tab',
        body: 'Keys are generated here and stay here, which also means nothing is backed up. Download the private keys before you close the tab, then move them somewhere you actually control.'
      },
      fingerprint: {
        title: 'Publish the fingerprint',
        body: 'A signature proves the file is unchanged since signing, not who wrote it. Put the fingerprint in the material that recruits participants; the consent screen shows them the same eight groups to compare it against.'
      },
      disclosure: {
        title: 'The app describes the data, not you',
        body: 'Before consent, the app lists every collector you enabled and describes it in its own words, with your parameters filled in. Write your consent summary to agree with that screen — it is the one thing here you cannot phrase more mildly.'
      }
    },
    keys: {
      signing: {
        title: 'Study signing key',
        algorithm: 'Ed25519',
        role: 'Signs the configuration.',
        risk: 'Lost: no new files under this key ID. Leaked: anyone can sign as you, and there is no revocation.'
      },
      export: {
        title: 'Export encryption key',
        algorithm: 'X25519 · HPKE',
        role: 'Decrypts every export and every upload.',
        risk: 'Lost: every bundle is unreadable. No escrow, no recovery, and devices cannot re-encrypt.'
      },
      handling:
        'A downloaded private key is an ordinary file. Move it into your controlled environment, and keep it out of Git, chat, and the study configuration.',
      replace: 'Generating again discards the pair held here. Download first.'
    },
    study: {
      section: {
        identity: {
          title: 'Identity',
          note: 'One experiment, many configurations. Change anything below and the configuration ID changes too.'
        },
        validity: {
          title: 'Validity',
          note: 'Verification needs the current time inside this window. Keep it short — an issued file cannot be revoked.'
        },
        about: {
          title: 'The study',
          note: 'Every word here is signed and shown untranslated, in whatever language you write it. A study recruiting in two languages needs two signed configurations.'
        },
        consent: {
          title: 'Consent',
          note: 'Change this and you need a new configuration ID, a new signature, and consent again.'
        },
        collectors: {
          title: 'Data',
          note: 'Fewest sources, lowest usable rate, shortest duration. This is space and battery on someone’s own phone.'
        },
        prompts: {
          title: 'Prompts',
          note: 'Scheduling is inexact. Do not design a protocol that needs a prompt to land on a particular minute.'
        },
        storage: {
          title: 'Storage',
          note: '8 MiB to 8 GiB. When the quota fills, the study stops collecting rather than dropping events.'
        },
        delivery: {
          title: 'Delivery',
          note: 'Off, and nothing leaves the phone until a participant exports it. On, and a participant cannot decline delivery while taking part — your consent text has to say so.'
        }
      }
    },
    sign: {
      canonical: 'Canonical JSON',
      size: ({ bytes, max }) => `${number.format(bytes)} / ${number.format(max)} bytes`,
      blocked: (count) =>
        count === 1 ? '1 problem to fix' : `${number.format(count)} problems to fix`,
      publish: 'Publish this where you recruit. It is what a participant compares.'
    },
    files: {
      keep: 'Private keys go somewhere you control.',
      archive:
        'researcher-tools decrypt --config reads this file, and no command extracts one from the .adccfg. Without it you cannot read your own data at the end of the study.',
      publish: 'The fingerprint goes into your recruitment material.',
      distribute: 'The .adccfg goes to participants.',
      pilot: 'Pilot on the Android versions and hardware your study targets before you recruit anyone.'
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
