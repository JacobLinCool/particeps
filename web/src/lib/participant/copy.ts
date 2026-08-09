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
 *   - No claim that a signature or fingerprint identifies who wrote a file.
 *   - Collector limits shown by the app must be narrow guarantees grounded in the implementation,
 *     not an exhaustive threat model. The data dictionary remains the complete field reference.
 *   - No "anonymous", no hardware-backed key, and nothing about a research team's intentions.
 *     The install code is pseudonymous, the app requests no StrongBox, and this page cannot know
 *     anything at all about the team that recruited its reader.
 *
 * Where the app already has the sentence — a collector description, a consent-screen block, or a
 * setup-step label — it is transcribed verbatim from the app's `strings.xml` in both languages, so
 * the page and the phone say the same words rather than two paraphrases of them.
 */

export interface ParticipantCopy {
  hero: {
    title: string;
    lead: string;
    download: string;
    caption: string;
    disclaimer: string;
    /**
     * The product's name, said once, where a reader first meets it — and, in the second string,
     * the half a name cannot be trusted to carry. Taking part is not the same as setting the
     * terms: a note that gave only what the app holds for its reader would be an argument for
     * the name rather than a description of the product.
     */
    naming: { name: string; limits: string };
  };

  glance: {
    collect: string;
    where: string;
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
    more: string;
    moreLink: string;
    name: {
      appLifecycle: string;
      accelerometer: string;
      batteryState: string;
      temporalContext: string;
      gyroscope: string;
      ambientLight: string;
      proximity: string;
      networkState: string;
      networkUsage: string;
      usageEvents: string;
      location: string;
      keyboardTouch: string;
    };
    detail: {
      appLifecycle: string;
      accelerometer: string;
      batteryState: string;
      temporalContext: string;
      gyroscope: string;
      ambientLight: string;
      proximity: string;
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
  };
}

export const en: ParticipantCopy = {
  hero: {
    title: 'On your phone, and under your control',
    lead: 'This app records only what your study lists, keeps it encrypted on your phone, and starts only after you agree.',
    download: 'Download App',
    caption:
      'Sources feed into a phone, and what they record is encrypted inside it. Whether anything leaves on its own is stated on your consent screen, before you agree.',
    disclaimer:
      'This page is not your consent document. Where they differ, your consent document and your research team’s answers come first.',
    naming: {
      name: 'Particeps is Latin for one who takes part in something. What the app records is written to your phone and encrypted there, you see every source your study switched on before you agree, and nothing is collected until you press Start.',
      limits:
        'The name is not a promise that the study is yours to set. What a study may collect, and how long it runs, are fixed in the signed study file your research team gives you: you can decline the study, or leave it, but you cannot rewrite it. Inside a study you can hold back the sources it marks optional, by not granting the access they ask for; a source it marks required stops the study instead. Nothing that has already left your phone can be taken back.'
    }
  },

  glance: {
    collect: 'What it records',
    where: 'Where it goes'
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
    title: 'Twelve sources exist. Your study uses some of them.',
    lead: 'Before you agree, the app lists the ones your study switched on — in the app’s own words, not the research team’s.',
    tokens: 'N, T and D come from your study: how often, how long between, how far.',
    more: 'That screen also names selected limits the implementation guarantees.',
    moreLink: 'The guide has the complete field table.',
    name: {
      appLifecycle: 'App activity',
      accelerometer: 'Motion',
      batteryState: 'Battery state',
      temporalContext: 'Time context',
      gyroscope: 'Rotation',
      ambientLight: 'Ambient light',
      proximity: 'Proximity',
      networkState: 'Connection type',
      networkUsage: 'Data volume',
      usageEvents: 'App and screen use',
      location: 'Location',
      keyboardTouch: 'Keyboard touch'
    },
    detail: {
      appLifecycle: 'When this app itself is opened and closed',
      accelerometer: 'Movement of the phone, about {n} times per second or more',
      batteryState: 'Battery percentage, charging source, and power-save state',
      temporalContext: 'Time zone, UTC offset, daylight-saving state, and clock changes',
      gyroscope: 'Rotation of the phone around three axes, about {n} times per second or more',
      ambientLight: 'Illuminance reported by the phone’s ambient-light sensor',
      proximity: 'Raw distance, sensor range, and the phone’s near/far interpretation',
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
      code: 'The encrypted content includes a randomly generated installation code so the research team can tell participants apart after decrypting it. It contains no name and no account.',
      mandatory: 'Automatic sending is part of the study and cannot be switched off on its own.',
      caption: 'The same phone. Your export, and a second arrow that repeats on a schedule.',
      metadata:
        'The receiving server sees when a delivery arrived and how large it was, plus bundle, configuration, digest, and claimed range metadata. It cannot see your installation code or collected content.'
    },
    // Sealing is a property of the key, and the receiving computer is where the other half of that
    // key may well live. The network claim is the one this software actually makes.
    sealed:
      'What leaves is sealed to a key only the research team holds. The network it crosses cannot open it.',
    exportable: 'You can export a copy yourself, as often as you like, in either kind of study.'
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

  coda: 'Advancing research while keeping data safe.',

  footer: {
    guide: 'Participant guide',
    source: 'Source code',
    note: 'This page runs entirely in your browser. It has no analytics and sends nothing anywhere.',
    researchers: 'For researchers'
  },

  a11y: {
    sample: 'Every value in this card is an example. Yours are on your consent screen.'
  }
};

export const zhTW: ParticipantCopy = {
  hero: {
    title: '手機裡的資料由你掌控',
    lead: '這個 App 只會記錄研究中列出的項目，並在資料寫入手機時立即加密。只有在你同意後，App 才會開始收集。',
    download: '下載 App',
    caption: 'App 會將各資料來源的紀錄加密儲存在手機中。同意畫面會在你同意前清楚說明資料是否會自動傳送。',
    disclaimer: '本頁不是研究同意書。若內容不一致，請以同意書及研究團隊的說明為準。',
    naming: {
      name: 'Particeps 是拉丁文，意思是「參與其中的人」。App 記錄的資料會寫入你的手機並在當下加密；在你同意之前，App 會先列出這項研究啟用的每一項資料來源；按下「開始研究」之前不會收集任何資料。',
      limits:
        '這個名稱不代表研究內容由你決定。研究可以收集哪些資料、進行多久，都寫定在研究團隊交給你的那份已簽署設定檔裡：你可以拒絕參與，也可以中途離開，但無法改寫它。在一項研究裡，你能保留不給的只有標示為選用的資料來源——不授予它要求的權限，它就不會啟用；標示為必要的來源則會直接讓研究無法進行。已經離開手機的資料也無法收回。'
    }
  },

  glance: {
    collect: '記錄什麼',
    where: '資料去哪'
  },

  setup: {
    title: '按下「開始研究」之前不會收集任何資料',
    lead: '匯入設定檔不會收集任何資料。五個步驟裡，同意是第三步。',
    step: { study: '研究', data: '資料', consent: '同意', access: '權限', start: '開始' },
    caption: {
      study: '誰在邀請、為什麼、要多久',
      data: '這個研究啟用的每一項資料來源',
      consent: '研究團隊提供的同意書、簽章及資料傳送方式',
      access: '這些來源需要的 Android 權限',
      start: '從這裡開始收集'
    },
    marker: '開始研究',
    note: '你可以在任何一步離開。',
    language: 'App 預設使用手機的語言，也可以點選右上角的地球圖示切換語言。'
  },

  sources: {
    title: 'App 支援十二種資料來源，每項研究只會啟用其中幾種。',
    lead: '取得你的同意前，App 會列出這項研究啟用的資料來源。這些說明由 App 提供，研究團隊無法修改。',
    tokens: 'N、T、D 分別代表取樣頻率、時間間隔與移動距離。',
    more: '該畫面也會列出部分由實作保證的限制。',
    moreLink: '完整的收集範圍請見參與者指南。',
    name: {
      appLifecycle: 'App 使用狀況',
      accelerometer: '手機移動',
      batteryState: '電池狀態',
      temporalContext: '時間脈絡',
      gyroscope: '旋轉',
      ambientLight: '環境光',
      proximity: '距離感測',
      networkState: '連線類型',
      networkUsage: '流量',
      usageEvents: 'App 與螢幕使用',
      location: '位置',
      keyboardTouch: '鍵盤觸控'
    },
    detail: {
      appLifecycle: '這個 App 本身開啟與關閉的時間',
      accelerometer: '手機的移動狀況，每秒約記錄 {n} 次以上',
      batteryState: '電量百分比、充電來源與省電模式狀態',
      temporalContext: '時區、UTC 偏移、日光節約時間狀態與系統時間變更',
      gyroscope: '手機繞三軸旋轉的角速度，每秒約記錄 {n} 次以上',
      ambientLight: '手機環境光感測器回報的照度',
      proximity: '原始距離、感測器最大範圍與手機的遠近判定',
      networkState: '目前使用 Wi-Fi 或行動網路，以及連線是否按流量計費',
      networkUsage: '每隔 {t} 記錄手機傳送與接收的總位元組數',
      usageEvents: '每隔 {t} 記錄開啟或關閉了哪些 App，以及螢幕何時亮起',
      location: '約每隔 {t} 記錄一次手機位置，移動超過 {d} 才會記錄',
      keyboardTouch: '你在研究鍵盤內的觸控方式，包括觸碰位置、時間與力道'
    }
  },

  delivery: {
    title: '資料會去哪裡',
    lead: '研究有兩種資料傳送方式，同意畫面會說明這項研究採用哪一種。',
    local: {
      title: '這個研究不會自動傳送資料',
      body: '在你自行匯出前，資料只會留在這支手機。',
      caption: '圖中只有你自行匯出時才會出現往外的箭頭。'
    },
    upload: {
      title: '這個研究會自動傳送資料',
      destination: '傳送對象',
      cadence: '傳送頻率',
      network: '網路條件',
      sampleHost: 'study.example.org',
      sampleCadence: '約每 6 小時',
      sampleNetwork: '只用 Wi-Fi',
      code: '加密內容包含一組隨機產生的安裝代碼，研究團隊解密後可用它區分不同的 App 安裝。代碼不含你的姓名或帳號。',
      mandatory: '自動傳送是這個研究的一部分，無法單獨關閉。暫停或退出研究會停止收集新資料，但已收集且尚未傳送的資料仍會傳送給研究團隊。',
      caption: '一個箭頭代表你自行匯出，另一個代表 App 依排程自動傳送。',
      metadata:
        '接收伺服器會看到傳送抵達的時間與大小，以及 bundle、設定摘要、內容摘要和宣告的資料範圍；它看不到安裝代碼或收集內容。'
    },
    sealed: '資料離開手機時會加密，只有研究團隊持有的解密金鑰能開啟內容，傳輸途中無法讀取。',
    exportable: '無論採用哪種傳送方式，你都可以不限次數自行匯出資料。'
  },

  flags: {
    title: '遇到以下情況，先停下來問清楚',
    fingerprint: '指紋不符，或研究團隊從未提供指紋',
    contents: '研究內容與研究團隊先前的說明不一致',
    scope: '研究要求的資料或權限超出預期',
    origin: 'App 並非從研究團隊指定的來源安裝',
    demo: '畫面顯示「載入展示研究」，表示這是開發版本。展示研究使用的私鑰也是公開的',
    password: '任何畫面要求你輸入密碼或關閉 Android 的安全設定'
  },

  coda: '在保障資料安全的前提下推進研究。',

  footer: {
    guide: '參與者指南',
    source: '原始碼',
    note: '這個頁面完全在你的瀏覽器裡執行，不含分析追蹤，也不會傳送任何資料。',
    researchers: '研究者專區'
  },

  a11y: {
    sample: '卡片中的所有內容都只是範例，實際設定請以同意畫面為準。'
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
