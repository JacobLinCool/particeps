/**
 * The participant page's catalogue.
 *
 * Every string this page renders is here, in both locales, and every component below takes a
 * dotted key rather than a sentence. It sits beside the page rather than in `lib/i18n` because
 * `Messages` is the shape the two *product* surfaces share — controls, field labels, issue codes —
 * and this is a single document's prose, which is the one place on the site prose is allowed.
 *
 * Three rules the wording is held to, all of them from `docs/threat-model.md`:
 *
 *   - No claim that a signature shows who wrote a file. The whole of section 5 exists because it
 *     does not.
 *   - No claim that a source cannot see something. The app's data step is positive by design; the
 *     negative claims are the data dictionary's, written against the code. The one exception is
 *     the keyboard line, which withdraws reassurance rather than offering it.
 *   - No "anonymous", no hardware-backed key, and nothing about a research team's intentions.
 *     The install code is pseudonymous, the app requests no StrongBox, and this page cannot know
 *     anything at all about the team that recruited its reader.
 *
 * Where the app already has the sentence — a collector description, a consent-screen block, a
 * button — it is transcribed verbatim from the app's `strings.xml` in both languages, so the page
 * and the phone say the same words rather than two paraphrases of them.
 */

export interface ParticipantCopy {
  hero: {
    title: string;
    lead: string;
    caption: string;
    disclaimer: string;
  };

  glance: {
    collect: string;
    where: string;
    fingerprint: string;
    stop: string;
  };

  setup: {
    title: string;
    lead: string;
    /** The app's own five step names. */
    step: { study: string; data: string; consent: string; access: string; start: string };
    caption: { study: string; data: string; consent: string; access: string; start: string };
    marker: string;
    note: string;
    language: string;
  };

  sources: {
    title: string;
    lead: string;
    /** What the `{n}` / `{t}` / `{d}` pills stand for. */
    tokens: string;
    caution: string;
    more: string;
    moreLink: string;
    name: {
      appLifecycle: string;
      accelerometer: string;
      networkState: string;
      networkUsage: string;
      usageEvents: string;
      location: string;
      keyboardTouch: string;
    };
    detail: {
      appLifecycle: string;
      accelerometer: string;
      networkState: string;
      networkUsage: string;
      usageEvents: string;
      location: string;
      keyboardTouch: string;
    };
  };

  delivery: {
    title: string;
    lead: string;
    local: { title: string; body: string; caption: string };
    upload: {
      title: string;
      destination: string;
      cadence: string;
      network: string;
      sampleHost: string;
      sampleCadence: string;
      sampleNetwork: string;
      code: string;
      mandatory: string;
      caption: string;
      metadata: string;
    };
    sealed: string;
    exportable: string;
  };

  fingerprint: {
    title: string;
    lead: string;
    publishedTitle: string;
    publishedNote: string;
    /** Nothing here is authoritative, and the drawing says so too. */
    sample: string;
    pick: string;
    cardTitle: string;
    compare: string;
    unverified: string;
    match: string;
    mismatch: string;
    normal: string;
  };

  controls: {
    title: string;
    lead: string;
    axis: { collection: string; sending: string };
    effect: {
      continues: string;
      stops: string;
      drainsThenStops: string;
      alreadyStopped: string;
      none: string;
    };
    label: {
      export: string;
      pause: string;
      finish: string;
      withdraw: string;
      delete: string;
    };
    note: {
      export: string;
      pause: string;
      finish: string;
      withdraw: string;
      delete: string;
    };
    sending: string;
    recall: string;
    irreversible: string;
  };

  flags: {
    title: string;
    fingerprint: string;
    contents: string;
    scope: string;
    origin: string;
    demo: string;
    password: string;
  };

  coda: string;

  footer: {
    guide: string;
    source: string;
    note: string;
    researchers: string;
  };

  a11y: {
    sample: string;
    result: string;
  };
}

export const en: ParticipantCopy = {
  hero: {
    title: 'On your phone, and under your control',
    lead: 'This app records only what your study lists, keeps it encrypted on your phone, and starts only after you agree.',
    caption:
      'Sources feed into a phone, and what they record is encrypted inside it. Whether anything leaves on its own is stated on your consent screen, before you agree.',
    disclaimer:
      'This page is not your consent document. Where they differ, your consent document and your research team’s answers come first.'
  },

  glance: {
    collect: 'What it records',
    where: 'Where it goes',
    fingerprint: 'The one check',
    stop: 'Stopping'
  },

  setup: {
    title: 'Nothing is collected until you press Start',
    lead: 'Importing a study file collects nothing. Consent is the third of five steps.',
    step: { study: 'Study', data: 'Data', consent: 'Consent', access: 'Access', start: 'Start' },
    caption: {
      study: 'who is asking, why, how long',
      data: 'every source this study switched on',
      consent: 'their consent text, the signature, and whether it sends',
      access: 'the Android access it needs',
      start: 'collection begins here'
    },
    marker: 'Start study',
    note: 'You can leave at any step.',
    language: 'The app follows your phone’s language. The globe at its top right changes it.'
  },

  sources: {
    title: 'Seven sources exist. Your study uses some of them.',
    lead: 'Before you agree, the app lists the ones your study switched on — in the app’s own words, not the research team’s.',
    tokens: 'N, T and D come from your study: how often, how long between, how far.',
    caution:
      'No characters, no text, no clipboard — but how you type still says something about what you typed.',
    more: 'What a source cannot see is not on that screen.',
    moreLink: 'The guide has the full table.',
    name: {
      appLifecycle: 'App activity',
      accelerometer: 'Motion',
      networkState: 'Connection type',
      networkUsage: 'Data volume',
      usageEvents: 'App and screen use',
      location: 'Location',
      keyboardTouch: 'Keyboard touch'
    },
    detail: {
      appLifecycle: 'When this app itself is opened and closed',
      accelerometer: 'Movement of the phone, about {n} times per second or more',
      networkState: 'Whether you are on Wi-Fi or mobile data, and whether it is metered',
      networkUsage: 'Total bytes your phone sent and received, every {t}',
      usageEvents: 'Which apps open and close, and when the screen turns on, every {t}',
      location: 'Where the phone is, about every {t}, after it moves at least {d}',
      keyboardTouch:
        'How you touch the keys — position, timing, pressure — inside the research keyboard only'
    }
  },

  delivery: {
    title: 'Where it goes',
    lead: 'Two kinds of study. Your consent screen says which one yours is.',
    local: {
      title: 'This study does not send data automatically',
      body: 'What it collects stays on this phone until you export it yourself.',
      caption: 'The same phone. One arrow out, and you draw it.'
    },
    upload: {
      title: 'This study sends data automatically',
      destination: 'Sends to',
      cadence: 'How often',
      network: 'Network',
      sampleHost: 'study.example.org',
      sampleCadence: 'About every 6 hours',
      sampleNetwork: 'Wi-Fi only',
      code: 'A randomly generated code travels with the data so the team can tell participants apart. It contains no name and no account.',
      mandatory: 'Automatic sending is part of the study and cannot be switched off on its own.',
      caption: 'The same phone. Your export, and a second arrow that repeats on a schedule.',
      metadata:
        'A server that receives deliveries learns when each arrived and how large it was, plus the study’s identifiers and a random code for your install.'
    },
    // Sealing is a property of the key, and the receiving computer is where the other half of that
    // key may well live. The network claim is the one this software actually makes.
    sealed:
      'What leaves is sealed to a key only the research team holds. The network it crosses cannot open it.',
    exportable: 'You can export a copy yourself, as often as you like, in either kind of study.'
  },

  fingerprint: {
    title: 'One thing to check yourself',
    lead: 'A signature proves the file has not been altered since it was signed. It does not prove who wrote it. The fingerprint is what ties a study to the team that recruited you.',
    publishedTitle: 'Published by your research team',
    publishedNote:
      'from the sheet or the message you already had — not from the app, and not from this page',
    sample: 'Every fingerprint here is an example.',
    pick: 'Two studies. Pick one and compare it, group by group.',
    cardTitle: 'Configuration signature',
    compare: 'Check this against the fingerprint your research team published.',
    unverified:
      'A signature shows the file has not been altered since it was signed. It does not show who wrote it.',
    match: 'Same key. This is the file they published.',
    mismatch:
      'Different key. Do not consent — contact your team using details you already had, not details from the study screen.',
    normal: 'Most studies show “check this”. That is ordinary, not a warning.'
  },

  controls: {
    title: 'Stopping, exporting, deleting',
    lead: 'Every one of these is yours, and none of them asks you for a reason.',
    axis: { collection: 'Collection', sending: 'Sending' },
    effect: {
      continues: 'continues',
      stops: 'stops',
      drainsThenStops: 'what is already collected still goes, then stops',
      alreadyStopped: 'already stopped',
      none: 'unchanged'
    },
    label: {
      export: 'Export encrypted data',
      pause: 'Pause',
      finish: 'Finish early',
      withdraw: 'Withdraw',
      delete: 'Delete local data'
    },
    note: {
      export: 'Available in every state, as often as you like.',
      pause: 'Resume whenever you want.',
      finish: 'Ends the study. You cannot restart it.',
      withdraw: 'Ends the study permanently.',
      delete: 'Removes the encrypted events and the study from this phone.'
    },
    sending:
      'Pausing, finishing and withdrawing stop collection. In a study that sends automatically, what was already collected still goes. Deleting is what stops that.',
    recall:
      'Withdrawing does not recall what already left your phone. To have the research team delete their copy, ask them.',
    irreversible: 'Deletion cannot be undone. Export first if you want your own copy.'
  },

  flags: {
    title: 'Stop and ask if…',
    fingerprint: 'the fingerprint does not match, or you were never given one',
    contents: 'the study’s contents differ from what you were told',
    scope: 'it asks for more than you expected',
    origin: 'the app did not come from the source your team gave you',
    demo: 'you see Load demo study — a development build, whose keys are public',
    password: 'anything asks for a password, or to turn off an Android security setting'
  },

  coda: 'Declining is a complete answer. You do not have to give a reason.',

  footer: {
    guide: 'Participant guide',
    source: 'Source code',
    note: 'This page runs entirely in your browser. It has no analytics and sends nothing anywhere.',
    researchers: 'For researchers'
  },

  a11y: {
    sample: 'Every value in this card is an example. Yours are on your consent screen.',
    result: 'Comparison result'
  }
};

export const zhTW: ParticipantCopy = {
  hero: {
    title: '留在你的手機，由你決定',
    lead: '這個 App 只記錄你的研究列出的項目，記下來就加密存在手機裡，而且要等你同意才開始。',
    caption: '各種來源把資料送進手機，手機寫入的當下就加密。會不會自動傳出去，同意畫面會在你同意之前寫清楚。',
    disclaimer: '這個頁面不是你的同意書。兩邊講的不一樣時，以同意書和研究團隊的回答為準。'
  },

  glance: {
    collect: '記錄什麼',
    where: '資料去哪',
    fingerprint: '一項核對',
    stop: '如何停止'
  },

  setup: {
    title: '按下「開始研究」之前不會收集任何資料',
    lead: '匯入設定檔不會收集任何資料。五個步驟裡，同意是第三步。',
    step: { study: '研究', data: '資料', consent: '同意', access: '權限', start: '開始' },
    caption: {
      study: '誰在邀請、為什麼、要多久',
      data: '這個研究開啟的每一項來源',
      consent: '團隊的同意書、簽章，以及會不會自動傳送',
      access: '這些來源需要的 Android 權限',
      start: '從這裡開始收集'
    },
    marker: '開始研究',
    note: '你可以在任何一步離開。',
    language: 'App 會跟隨手機的語言，右上角的地球圖示可以更改。'
  },

  sources: {
    title: '總共有七種來源，你的研究只會用到其中幾種。',
    lead: '在請你同意之前，App 會列出這個研究開啟的來源。那些說明是 App 自己寫的，不是研究團隊寫的。',
    tokens: '你的研究會填入 N、T、D：多久一次、間隔多長、距離多遠。',
    caution: '不含字元、不含文字、不含剪貼簿。但你打字的方式，多少還是會透露你打了什麼。',
    more: '那個畫面不會說某個來源看不到什麼。',
    moreLink: '完整的對照表在指南裡。',
    name: {
      appLifecycle: 'App 使用狀況',
      accelerometer: '動作',
      networkState: '連線類型',
      networkUsage: '流量',
      usageEvents: 'App 與螢幕使用',
      location: '位置',
      keyboardTouch: '鍵盤觸控'
    },
    detail: {
      appLifecycle: '這個 App 本身何時開啟與關閉',
      accelerometer: '手機的移動，每秒約 {n} 次或更多',
      networkState: '你現在用 Wi-Fi 還是行動網路，這個連線會不會計費',
      networkUsage: '手機每 {t} 傳送與接收的總位元組數',
      usageEvents: '每 {t} 記錄哪些 App 開啟關閉、螢幕何時亮起',
      location: '手機所在位置，約每 {t} 一次，移動超過 {d} 才記錄',
      keyboardTouch: '你在研究鍵盤裡按鍵的方式：位置、時間、力道'
    }
  },

  delivery: {
    title: '資料會去哪裡',
    lead: '研究分成兩種，你的同意畫面會說明你參加的是哪一種。',
    local: {
      title: '這個研究不會自動傳送資料',
      body: '你不自己匯出，資料就只留在這支手機。',
      caption: '同一支手機。只有一個往外的箭頭，而且是你自己畫的。'
    },
    upload: {
      title: '這個研究會自動傳送資料',
      destination: '傳送對象',
      cadence: '傳送頻率',
      network: '網路條件',
      sampleHost: 'study.example.org',
      sampleCadence: '約每 6 小時',
      sampleNetwork: '只用 Wi-Fi',
      code: '傳送的資料會附帶一組隨機產生的代號，讓研究團隊分辨不同參與者。代號不含你的姓名，也不含帳號。',
      mandatory: '自動傳送是這個研究的一部分，無法單獨關閉。',
      caption: '同一支手機。一個箭頭是你自己匯出的，另一個會按排程重複。',
      metadata:
        '接收資料的伺服器會知道每次傳送的時間和大小，也會看到研究的識別碼和你這次安裝的隨機代號。'
    },
    sealed: '離開手機的資料，會用只有研究團隊有的金鑰封起來。中途經過的網路打不開。',
    exportable: '兩種研究你都可以自己匯出一份，想匯出幾次都可以。'
  },

  fingerprint: {
    title: '有一件事需要你親自核對',
    lead: '簽章證明設定檔簽署後沒有人改過，但不能證明是誰寫的。金鑰指紋才能把研究和邀請你的團隊連起來。',
    publishedTitle: '研究團隊公布的指紋',
    publishedNote: '來自你原本就拿到的紙本或訊息，不是來自 App，也不是來自這個頁面',
    sample: '這裡的每一組指紋都只是範例。',
    pick: '兩個研究。挑一個，一組一組核對看看。',
    cardTitle: '設定檔簽章',
    compare: '請拿研究團隊公布的指紋來核對。',
    unverified: '簽章證明設定檔簽署後沒有人改過，但不能證明是誰寫的。',
    match: '金鑰相同，這就是研究團隊公布的檔案。',
    mismatch: '金鑰不同，不要同意。請用你原本就有的聯絡方式找團隊，不要用研究畫面上顯示的聯絡方式。',
    normal: '大多數研究都會顯示「請核對」，這是常態，不是警告。'
  },

  controls: {
    title: '停止、匯出、刪除',
    lead: '以下每一項都是你的權利，而且 App 不會問你理由。',
    axis: { collection: '收集', sending: '傳送' },
    effect: {
      continues: '繼續',
      stops: '停止',
      drainsThenStops: '已經收集的資料還是會送出，之後停止',
      alreadyStopped: '早已停止',
      none: '不受影響'
    },
    label: {
      export: '匯出加密資料',
      pause: '暫停',
      finish: '提早完成',
      withdraw: '退出研究',
      delete: '刪除本機資料'
    },
    note: {
      export: '任何狀態下都可以使用，想匯出幾次都可以。',
      pause: '想繼續的時候再繼續。',
      finish: '結束這個研究，之後不能重新開始。',
      withdraw: '永久結束這個研究。',
      delete: '把加密的事件和研究一起從這支手機刪掉。'
    },
    sending:
      '暫停、提早完成和退出都會停止收集。在會自動傳送的研究裡，已經收集的資料還是會送出。要停止傳送，只能刪除。',
    recall: '退出不會收回已經離開手機的資料。想刪掉研究團隊手上那一份，要直接向他們提出。',
    irreversible: '刪除無法復原。想留一份自己的，請先匯出。'
  },

  flags: {
    title: '遇到以下情況，先停下來問清楚',
    fingerprint: '指紋對不起來，或是根本沒有人給過你指紋',
    contents: '研究的內容和別人告訴你的不一樣',
    scope: '要求的東西比你預期的多',
    origin: 'App 不是從團隊給你的來源安裝的',
    demo: '你看到「載入展示研究」。那是開發版，金鑰是公開的',
    password: '有任何地方要你輸入密碼，或要你關掉 Android 的安全設定'
  },

  coda: '拒絕就是完整的答案，你不需要說明理由。',

  footer: {
    guide: '參與者指南',
    source: '原始碼',
    note: '這個頁面完全在你的瀏覽器裡執行，沒有分析追蹤，也不會把任何東西送出去。',
    researchers: '研究者專區'
  },

  a11y: {
    sample: '這張卡片裡的每個值都只是範例，你的實際設定會顯示在同意畫面上。',
    result: '核對結果'
  }
};

/** Every dotted path to a string in the catalogue, and nothing else. */
type Paths<T, Prefix extends string = ''> = {
  [K in keyof T & string]: T[K] extends string
    ? Prefix extends ''
      ? K
      : `${Prefix}.${K}`
    : Paths<T[K], Prefix extends '' ? K : `${Prefix}.${K}`>;
}[keyof T & string];

export type MessageKey = Paths<ParticipantCopy>;
