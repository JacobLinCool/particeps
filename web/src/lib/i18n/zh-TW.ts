/**
 * Traditional Chinese (Taiwan). Kept key-for-key with `en.ts` — `tests/i18n.spec.ts` fails if one
 * file grows a key the other lacks, because a missing key here would silently render nothing.
 */

import type { Messages } from './types';

const number = new Intl.NumberFormat('zh-TW');

export const zhTW: Messages = {
  app: {
    name: 'Android Data Collector',
    tagline: '不必自己做 App，也能進行研究。',
    nav: { researcher: '研究者', participant: '參與者' }
  },

  language: {
    label: '語言',
    system: '跟隨瀏覽器',
    en: 'English',
    zhTW: '正體中文'
  },

  action: {
    generate: '產生',
    sign: '簽署',
    download: '下載',
    copy: '複製',
    importDraft: '匯入 JSON',
    back: '上一步',
    next: '下一步',
    confirmSaved: '檔案已存好',
    skip: '跳到主要內容',
    startOver: '重新開始',
    confirm: '確認',
    cancel: '取消'
  },

  intervention: {
    title: '介入活動與問卷',
    empty: '沒有排定的活動。',
    notificationTiming: 'Android 會盡力依排程發出通知，但不保證精準的時刻。',
    anonymous: '匿名／假名',
    personalized: '個人化',
    assignedId: '指定參與者代碼',
    addNotification: '新增通知',
    addSurvey: '新增問卷',
    addQuestion: '新增題目',
    addTrigger: '新增排程',
    survey: '問卷',
    surveyTitle: '問卷標題',
    surveyDescription: '問卷說明',
    question: '題目 ID',
    questionType: '題型',
    prompt: '題目文字',
    required: '必填',
    maximumLength: '最多字元數',
    scaleBounds: '量尺最小值／最大值',
    endpointLabels: '兩端標籤',
    options: '選項（穩定 ID | 標籤，每行一項）',
    selectionBounds: '最少／最多選項數',
    notificationTitle: '通知標題',
    notificationMessage: '通知訊息',
    trigger: '排程 ID',
    scheduleType: '排程類型',
    clock: '相對時間算法',
    offset: '延後分鐘數',
    interval: '間隔分鐘數',
    localTime: '本地時間',
    availability: '可填寫分鐘數',
    types: { shortText: '簡短文字', scale: '數字量尺', singleChoice: '單選', multipleChoice: '複選' },
    schedules: { oneTime: '單次', interval: '固定間隔', dailyLocal: '每日本地時間' },
    clocks: { calendar: '日曆時間（包含暫停）', active: '收集中時間（排除暫停）' }
  },

  control: {
    language: '語言',
    details: '詳細資訊',
    remove: '移除',
    reveal: '顯示',
    conceal: '隱藏',
    progress: '進度',
    print: '列印指紋',
    copyFingerprint: '複製指紋',
    applySuggestion: '使用這個 ID',
    timezone: '時區',
    stepPosition: ({ index, total }) => `第 ${index} 步，共 ${total} 步`
  },

  step: {
    keys: '金鑰',
    study: '研究',
    sign: '簽署',
    files: '檔案'
  },

  unit: {
    microseconds: 'µs',
    milliseconds: 'ms',
    // 跟 `Intl.NumberFormat` 短單位輸出的字一模一樣，數字框旁邊的字要跟讀數用的字一致。
    seconds: '秒',
    minutes: '分鐘',
    hours: '小時',
    hertz: 'Hz',
    metres: '公尺',
    mebibytes: 'MiB',
    bytes: '位元組'
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
      experimentId: '實驗 ID',
      configurationId: '設定檔 ID',
      issuedAt: '開始',
      expiresAt: '結束',
      title: '研究名稱',
      researcherName: '研究者',
      researcherContact: '聯絡方式',
      purpose: '研究目的',
      durationHours: '每個人做多久',
      consentDocumentVersion: '同意書版本',
      consentSummary: '同意書摘要',
      storageQuota: '最多能用多少空間',
      required: '必要',
      samplingPeriod: '取樣週期',
      reportLatency: '最長回報延遲',
      bandwidthEstimates: '頻寬估計值',
      transports: '網路類型',
      pollInterval: '輪詢間隔',
      interval: '定位間隔',
      fastestInterval: '最快更新間隔',
      batchDelay: '批次延遲',
      displacement: '最小位移',
      priority: '定位模式',
      trajectoryRate: '軌跡取樣率',
      upload: '自動傳送',
      endpoint: '接收端點',
      uploadInterval: '傳送間隔',
      allowMetered: '允許行動網路',
      signerKeyId: '簽章金鑰 ID',
      signerPublicKey: '簽章公鑰',
      exportKeyId: '匯出金鑰 ID',
      exportKeyset: '匯出公鑰 keyset',
      fingerprint: '金鑰指紋'
    },
    /**
     * 這裡不能宣稱、也不能暗示耗電或耗能。專案沒有量過，提示文字也不是用來猜的地方。量得出來的
     * 是位元組與時數，那些由儲存空間的量表來說。
     */
    hint: {
      override: '留空就會使用上面那組 ID。',
      contact: '參與者真的聯絡得上你的方式。',
      expiresAt: '這天之後就不能再加入了。',
      duration: '從參與者開始的那天算起。',
      consentSummary: '資料、目的、時長、風險、權限、匯出、退出、刪除、聯絡方式。',
      storageQuota: '滿了就停止收集，不會丟掉已經收到的資料。',
      required: '沒有這項權限就無法開始。',
      samplingPeriod: '這是請求，不是上限，裝置可能給得更快。',
      bandwidthEstimates: '平台的估計值，不是實測值。',
      pollInterval: '一分鐘是試跑用的設定，不是正式研究的設定。',
      fastestInterval: '不能比定位間隔長。',
      batchDelay: '設得越大，送出的次數越少，一次送的越多。',
      priority: '兩種模式都需要精確位置。高精確度會用 GPS。',
      endpoint: '必須是 https，而且由你自己營運。',
      allowMetered: '關閉時只走 Wi-Fi。行動網路要花錢，沒有人同意過要付。'
    }
  },

  option: {
    transport: { wifi: 'Wi-Fi', mobile: '行動網路' },
    priority: { balanced: '平衡', highAccuracy: '高精確度' }
  },

  collector: {
    'app_lifecycle.v1': {
      name: 'App 使用狀況',
      records: '這個 App 自己的畫面何時開啟與關閉',
      limit: '與其他 App 的使用無關'
    },
    'accelerometer.v1': {
      name: '動作',
      records: '裝置座標系的原始 x/y/z 加速度，含重力',
      limit: '沒有動作、姿勢或手勢的標記'
    },
    'network_state.v1': {
      name: '連線類型',
      records: '連線類型、是否計費、漫遊與驗證狀態',
      limit: '不含 SSID、位址、連線對象或內容'
    },
    'network_usage.v1': {
      name: '流量',
      records: '整支手機的位元組與封包總數，各類連線分開算',
      limit: '分不出是哪個 App，也看不出確切時間'
    },
    'usage_events.v1': {
      name: 'App 與螢幕使用',
      records: 'App 切換與螢幕事件，含套件名稱',
      limit: '會延遲也會遺漏，不是完整的使用序列'
    },
    'location.v1': {
      name: '位置',
      records: '定位點，含精確度、速度、高度與方位',
      limit: '取樣的估計值，會有缺口，不是連續軌跡'
    },
    'keyboard_touch.v1': {
      name: '鍵盤觸控',
      records: '按鍵內的相對位置、時間、力道與觸控面積',
      limit: '僅限研究鍵盤。不含文字，也不含按了哪個鍵。'
    }
  },

  issue: {
    required: '必填',
    id_format: '小寫英數字與連字號，3 至 64 個字元',
    length_range: ({ min, max }) => `${number.format(min)} 至 ${number.format(max)} 個字元`,
    number_range: ({ min, max }) => `須介於 ${number.format(min)} 到 ${number.format(max)} 之間`,
    integer: '只能是整數',
    instant: '無法辨識這個日期時間',
    window_order: '要比開始時間晚',
    collectors_empty: '至少要啟用一個資料來源',
    duplicate_id: '這個研究裡已經用過',
    transports_empty: '至少選一項',
    location_interval_order: '不能超過定位間隔',
    endpoint_scheme: '必須以 https:// 開頭',
    endpoint_host: '這個位址沒有主機名稱',
    document_too_large: ({ max }) => `整份設定必須小於 ${number.format(max)} 個位元組`,
    signer_missing: '請先產生簽章金鑰',
    export_key_missing: '請先產生匯出金鑰',
    keyset_unusable: 'App 無法用這組金鑰加密。請重新產生匯出金鑰，或改匯入一組',
    language_tag: '請使用有效的 BCP 47 語言標籤',
    unknown_reference: '請選擇這份設定中已定義的問卷',
    selection_bounds: '選取數量限制與這題不相容',
    schedule_bounds: '排程超出研究期間，或產生過多次活動'
  },

  status: {
    copied: '已複製',
    verified: '這裡驗證過簽章了',
    stale: '簽署後又有更動，請重新簽署。',
    clean: '沒有問題'
  },

  empty: {
    files: '要先簽署設定檔，才會有檔案可以交付。'
  },

  error: {
    insecureContext: '產生金鑰需要安全連線，請用 https 開啟這個頁面。',
    unsupportedBrowser: '這個瀏覽器無法產生金鑰。',
    signing: '簽署失敗，沒有寫出任何檔案。',
    draft: '這不是研究設定檔，這個頁面讀不了。',
    keyFile: '這不是私鑰檔案，這個頁面讀不了。',
    clipboard: '複製失敗，請自己選取文字複製。',
    notFound: '找不到這個頁面。'
  },

  confirm: {
    startOver: {
      title: '捨棄這個分頁裡的所有內容？',
      body: '金鑰與設定只存在這個分頁，沒有其他備份。還沒下載的東西都會消失。'
    },
    leave: { title: '金鑰還沒存檔，確定要離開？' },
    replaceKey: {
      title: '要換掉這個分頁裡的金鑰嗎？',
      body: '會捨棄這個分頁裡的金鑰組，改用新產生的一組。已經簽好的檔案仍然屬於原本的金鑰 ID。'
    }
  },

  researcher: {
    title: '準備一份研究',
    lede: '金鑰、設定、簽章都在這個分頁裡，不會外流。',
    how: {
      file: {
        title: '研究就是一個檔案',
        body: '你描述要收集什麼，用自己的金鑰簽署，再把檔案交給參與者。任何一版 App 都能執行這個檔案。不必自己寫 App，也不必部署任何東西。'
      },
      keys: {
        title: '兩把金鑰，兩種用途',
        body: '一把用來簽署研究，另一把用來解密收回來的資料。'
      },
      local: {
        title: '資料不會離開這個分頁',
        body: '這裡沒有任何備份，關掉分頁前先下載金鑰。'
      },
      fingerprint: {
        title: '公布金鑰指紋',
        body: '簽章只能證明檔案簽署後沒有改過，不能證明是誰寫的。請把金鑰指紋放進招募資料。同意畫面會顯示同樣的八組字元，參與者可以拿來核對。'
      },
      disclosure: {
        title: '資料說明是 App 寫的',
        body: '徵求同意前，App 會描述你啟用的每一個資料來源。'
      }
    },
    /**
     * 進到這一步時兩把金鑰都已經產生好了，所以這裡沒有任何要做決定的地方，只剩兩個檔案和一行紅字。
     * 兩句 risk 開頭同樣是「遺失：」，位置也同樣在磚上的同一個欄位，讀的人只要比後半句；只有匯出
     * 那一句是紅的。它們合起來說的是哪一種遺失只是重做一次，哪一種是資料本身。
     */
    keys: {
      signing: {
        title: '研究簽章金鑰',
        algorithm: 'Ed25519',
        risk: '遺失：再做一把就好。'
      },
      export: {
        title: '匯出加密金鑰',
        algorithm: 'X25519 · HPKE',
        /** 全站最窄的位置：檔案步驟的下載磚裡，那一條只有 135px。 */
        risk: '遺失：資料解不開。'
      },
      /** 檔案步驟裡「把金鑰存好」那一欄的提示，寬度 218px。全站唯一一句講外洩的話，
       *  放在歸檔的地方，而不是產生金鑰的地方。 */
      handling: '別放進 Git 或通訊軟體。',
      /** 收在預設關閉的摺疊區裡：沒有人被要求自備金鑰，但不代表不能。
       *  會這麼做的理由只有 reuseNote 那一句。 */
      reuse: '改用我已經有的金鑰',
      reuseNote: '同一把金鑰，指紋也一樣。'
    },
    study: {
      /** 五個標題，一句說明。標題底下的控制項已經說明白這一段在做什麼，
       *  只有 delivery 要補一件畫面上看不到的事。 */
      section: {
        about: { title: '研究內容' },
        validity: { title: '這個研究會進行多久？' },
        collectors: { title: '資料來源' },
        consent: { title: '知情同意' },
        interventions: { title: '介入活動' },
        delivery: {
          title: '自動傳送',
          note: '關閉時，資料要等參與者自己匯出才會離開手機。'
        }
      },
      /** 一句一行，放在它所說明的那個控制項旁邊。 */
      note: {
        irrevocable: '無法收回已經發出去的檔案。',
        /** 放在七張卡片上面，只說一次。每張卡片上的那顆按鈕都是同一個決定，
         *  決定帶來的後果也一樣，不必在卡片裡重複七遍。 */
        required: '標成「必要」的來源，參與者不同意就不能開始。',
        disclosure: 'App 會用自己的說法列出資料，摘要要跟 App 一致。',
        delivery: '拒絕傳送就等於不參與，同意書要寫清楚。'
      }
    },
    sign: {
      identity: {
        title: '識別碼',
        /** 現在是四列不是兩列：研究、設定檔，以及兩把金鑰。每一組都是推導出來的。 */
        note: '這些 ID 會跟著你的資料走，分析時要用。'
      },
      canonical: '正規化 JSON',
      size: ({ bytes, max }) => `${number.format(bytes)} / ${number.format(max)} 位元組`,
      blocked: (count) => `還有 ${number.format(count)} 個問題要處理`,
      publish: '請把這串字元公布在招募資料裡，參與者會拿指紋來核對。'
    },
    files: {
      keep: '把金鑰存好',
      /** 218px。「沒有任何指令能從 .adccfg 取回它」放不下，留在研究者指南裡；這裡放會影響決定的後果。 */
      archive: '解密自己的資料時會用到。',
      publish: '金鑰指紋放進招募資料。',
      distribute: '給參與者',
      pilot: '正式招募之前，先拿要支援的 Android 版本和機型實測一輪。'
    },
    cli: 'researcher-tools 在終端機裡能做同樣的事，而且還能解密收回來的資料。'
  },

  participant: {
    title: '在你同意之前',
    lede: '現在拒絕不用付出任何代價。先看看這個 App 會在你手機上做什麼、又會要求你什麼。',
    how: {
      start: {
        title: '按下開始之前，不會收集任何東西',
        body: '就算匯入了研究團隊給你的檔案，也還不會開始收集。設定共有五個畫面，你在任何一步都可以停下來。拒絕就是完整的答案，你不需要給任何人理由。'
      },
      list: {
        title: '同意之前，你會先看到清單',
        body: '其中一個畫面會列出來，這個研究開了哪些資料來源、各自多久取樣一次。那些說明文字是 App 寫的，不是研究團隊寫的。研究團隊的設定也改不了那些說法。'
      },
      storage: {
        title: '資料留在你的手機上',
        body: '事件寫入的當下就會加密。資料要送到研究團隊手上，得你自己匯出再傳送。也有研究會按排程自動傳送，同意畫面會在你同意之前寫清楚。'
      },
      fingerprint: {
        title: '核對金鑰指紋',
        body: '同意畫面上會顯示八組字元，每組四個。研究團隊應該公布過同樣的八組，就在你原本就信任的地方，請拿來核對。簽章只能證明沒有人改過檔案，不能證明是誰寫的。是誰寫的，要靠你自己核對指紋。如果對不起來，或根本沒人給過你，先問清楚再同意。'
      },
      control: {
        title: '你隨時可以停止',
        body: '暫停、提早完成或退出都可以，不需要理由，App 也不會問。之後你仍然可以匯出已收集的資料，也可以把手機上的資料全部刪除。要處理已經離開手機的資料，只能請研究團隊幫忙。'
      }
    },
    flow: {
      study: {
        title: '研究',
        body: '研究名稱、團隊、聯絡方式，以及會進行多久。'
      },
      data: {
        title: '資料',
        body: '啟用了哪些資料來源，以及各自多久取樣一次。'
      },
      consent: {
        title: '同意',
        body: '研究團隊寫的說明、簽章指紋，以及這個研究會不會自動傳送資料。'
      },
      access: {
        title: '權限',
        body: '這些來源需要的 Android 權限。標示為選用的權限可以不給。'
      },
      start: {
        title: '開始',
        body: '按下去才開始收集，沒按之前都不會。'
      }
    },
    note: '這個頁面描述的是 App 的行為。如果跟你參與的研究同意書不一樣，以同意書為準。',
    closing:
      '如果手機上看到的和別人告訴你的不一樣，先停下來問清楚。要用你原本就有的聯絡方式，不要用研究畫面上顯示的聯絡方式。'
  },

  link: {
    researcherGuide: '研究者指南',
    participantGuide: '參與者指南',
    threatModel: '威脅模型',
    source: '原始碼'
  }
};
