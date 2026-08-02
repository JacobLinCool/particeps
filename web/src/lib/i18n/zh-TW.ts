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
    addPrompt: '新增提示通知',
    back: '上一步',
    next: '下一步',
    confirmSaved: '檔案已存好',
    skip: '跳到主要內容',
    startOver: '重新開始',
    confirm: '確認',
    cancel: '取消'
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
      promptId: '提示通知 ID',
      promptDelay: '延遲',
      promptMessage: '訊息',
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
      title: '要用兩種語言招募，就要簽兩份檔案。',
      contact: '參與者真的聯絡得上你的方式。',
      expiresAt: '這天之後就不能再加入了。',
      duration: '從他們開始那天算起。',
      consentSummary: '資料、目的、時長、風險、權限、匯出、退出、刪除、聯絡方式。',
      storageQuota: '滿了就停止收集，不會丟掉已經收到的資料。',
      required: '沒有這項權限就無法開始。',
      samplingPeriod: '這是請求，不是上限，裝置可能給得更快。',
      bandwidthEstimates: '平台的估計值，不是實測值。',
      pollInterval: '一分鐘是試跑用的設定，不是正式研究的設定。',
      fastestInterval: '不能長於定位間隔。',
      batchDelay: '設得越大，送出的次數越少、每次越多。',
      priority: '兩者都需要精確位置；高精確度會用 GPS。',
      promptDelay: '自第一次開始起算。送達時間並不精確。',
      endpoint: '必須是 https，而且由你自己營運。',
      allowMetered: '關閉時只走 Wi-Fi。行動網路的費用不是參與者同意要付的。'
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
      records: '各類連線的裝置總位元組與封包數',
      limit: '無法分到個別 App，也看不出確切時間'
    },
    'usage_events.v1': {
      name: 'App 與螢幕使用',
      records: 'App 切換與螢幕事件，含套件名稱',
      limit: '會延遲也會遺漏，不是完整的使用序列'
    },
    'location.v1': {
      name: '位置',
      records: '定位點，含精確度、速度、高度與方位',
      limit: '是取樣的估計值且有缺口，不是連續軌跡'
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
    instant: '不是能辨識的日期時間',
    window_order: '必須晚於生效時間',
    collectors_empty: '至少要啟用一個資料來源',
    duplicate_id: '這個研究裡已經用過',
    transports_empty: '至少選一項',
    location_interval_order: '不能超過定位間隔',
    endpoint_scheme: '必須以 https:// 開頭',
    endpoint_host: '這個位址沒有主機名稱',
    document_too_large: ({ max }) => `整份設定必須小於 ${number.format(max)} 個位元組`,
    signer_missing: '請先產生簽章金鑰',
    export_key_missing: '請先產生匯出金鑰',
    keyset_unusable: '應用程式無法用這組金鑰加密。請重新產生或匯入匯出金鑰'
  },

  status: {
    copied: '已複製',
    verified: '簽章已在此驗證',
    stale: '簽署後又有更動，請重新簽署。',
    clean: '沒有問題'
  },

  empty: {
    prompts: '沒有提示通知，多數研究也不需要。',
    files: '設定檔簽署後才會有可以交付的檔案。'
  },

  error: {
    insecureContext: '產生金鑰需要安全的連線環境，請改用 https 開啟本頁。',
    unsupportedBrowser: '這個瀏覽器無法產生金鑰。',
    signing: '簽署失敗，沒有寫出任何檔案。',
    draft: '這個檔案不是本頁能讀取的研究設定檔。',
    keyFile: '這個檔案不是本頁能讀取的私鑰。',
    clipboard: '複製失敗，請自行選取文字複製。',
    notFound: '這個網址上沒有頁面。'
  },

  confirm: {
    startOver: {
      title: '捨棄這個分頁裡的所有內容？',
      body: '金鑰與設定只存在這個分頁，沒有其他備份。還沒下載的東西都會消失。'
    },
    leave: { title: '金鑰尚未存檔，確定要離開？' },
    replaceKey: {
      title: '要替換這裡持有的金鑰嗎？',
      body: '這個分頁裡的金鑰組會被捨棄，改用新產生的一組。已經簽好的檔案仍然屬於原本的金鑰 ID。'
    }
  },

  researcher: {
    title: '準備一份研究',
    beyond: '排在研究結束之後。準時完成的參與者永遠不會收到這則提示。',
    lede: '金鑰、設定、簽章都在這個分頁裡，不會外流。',
    how: {
      file: {
        title: '研究就是一個檔案',
        body: '你描述要收集什麼，用自己的金鑰簽署，再把檔案交給參與者。任何一版 App 都能執行它——不必自己寫 App，也沒有東西要部署。'
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
        body: '簽章只能證明檔案自簽署後未被更動，不能證明是誰寫的。請把金鑰指紋放進招募資料裡；同意畫面會顯示同樣的八組字元，讓參與者拿來核對。'
      },
      disclosure: {
        title: '資料說明由 App 撰寫',
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
      section: {
        about: {
          title: '研究內容',
          note: '你在這裡寫什麼，參與者就讀到什麼。'
        },
        validity: {
          title: '這個研究會進行多久？',
          note: '什麼時候開始、什麼時候結束，每個人要做多久？'
        },
        collectors: {
          title: '資料來源',
          note: '來源越少越好，頻率夠用就好。那是別人的手機。'
        },
        consent: {
          title: '知情同意',
          note: '這裡改了，就要重新簽署、重新徵求同意。'
        },
        prompts: {
          title: '提示通知',
          note: '送達時間不精確，不要指望準到分鐘。'
        },
        delivery: {
          title: '自動傳送',
          note: '關閉時，資料要等參與者自己匯出才會離開手機。'
        }
      },
      /** 一句一行，放在它所說明的那個控制項下面。 */
      note: {
        irrevocable: '已經發出去的檔案，收不回來。',
        disclosure: 'App 會用自己的說法列出資料，摘要要與它一致。',
        delivery: '參與者無法只拒絕傳送而繼續參與，同意書要寫清楚。'
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
      publish: '請把這串字元公布在招募資料裡，參與者會拿它來核對。'
    },
    files: {
      keep: '把金鑰存好',
      /** 218px。「沒有任何指令能從 .adccfg 取回它」放不下，留在研究者指南裡；這裡放會影響決定的後果。 */
      archive: '解密自己的資料時需要它。',
      publish: '金鑰指紋放進招募資料。',
      distribute: '給參與者',
      pilot: '正式招募之前，先在研究要支援的 Android 版本與機型上實測一輪。'
    },
    cli: 'researcher-tools 在終端機裡能做同樣的事，而且還能解密收回來的資料。'
  },

  participant: {
    title: '在你同意之前',
    lede: '趁著拒絕還不需要任何代價，先看看這個 App 會在你手機上做什麼、又會要求你什麼。',
    how: {
      start: {
        title: '按下開始之前，不會收集任何東西',
        body: '匯入研究團隊給你的檔案並不會開始收集。設定共有五個畫面，你在任何一步都可以停下來。拒絕就是完整的答覆，你不需要給任何人理由。'
      },
      list: {
        title: '同意之前，你會先看到清單',
        body: '其中一個畫面會列出這個研究啟用的每一個資料來源，以及它要求的頻率。那些說明文字屬於 App，不是研究團隊寫的——他們的設定改不了那些說法。'
      },
      storage: {
        title: '資料留在你的手機上',
        body: '事件在寫入的當下就會加密。要送到研究團隊手上，得由你自己匯出並傳送；除非這個研究會自動傳送，而那件事會在你同意之前，寫在同意畫面上。'
      },
      fingerprint: {
        title: '核對金鑰指紋',
        body: '同意畫面上會顯示八組、每組四個字元。研究團隊應該已經在你原本就信任的地方公布了同樣的八組，請拿來核對。簽章只能證明檔案未被竄改，不能證明是誰寫的，而這個核對正好補上那一段。如果對不起來，或根本沒人給過你，先問清楚再同意。'
      },
      control: {
        title: '你隨時可以停止',
        body: '暫停、提早完成或退出都可以，不需要理由，App 也不會問。之後你仍然可以匯出已收集的資料，也可以把手機上的資料全部刪除。已經離開手機的部分，只能請研究團隊處理。'
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
        body: '這些來源需要的 Android 權限；標示為選用的可以不給。'
      },
      start: {
        title: '開始',
        body: '按下去才開始收集，在此之前都不會。'
      }
    },
    note: '這個頁面描述的是 App 的行為。若與你參與的研究同意書有出入，以同意書為準。',
    closing:
      '如果手機上看到的和別人告訴你的不一樣，先停下來問清楚，而且要用你原本就有的聯絡方式，不要用研究畫面上顯示的。'
  },

  link: {
    researcherGuide: '研究者指南',
    participantGuide: '參與者指南',
    threatModel: '威脅模型',
    source: '原始碼'
  }
};
