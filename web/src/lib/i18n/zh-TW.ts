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
      issuedAt: '生效時間',
      expiresAt: '失效時間',
      minimumAppVersion: '最低 App 版本',
      title: '研究名稱',
      researcherName: '研究者',
      researcherContact: '聯絡方式',
      purpose: '研究目的',
      durationHours: '研究時長',
      consentDocumentVersion: '同意書版本',
      consentSummary: '同意書摘要',
      storageQuota: '本機儲存上限',
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
    hint: {
      id: '小寫英數字與連字號，3 至 64 個字元。',
      contact: '參與者真的聯絡得上你的方式。',
      duration: '自參與者第一次按下開始起算。',
      consentSummary:
        '需說明資料、目的、時長、風險、需要的權限、匯出、退出、刪除、你們會保留什麼，以及如何聯絡你。',
      minimumAppVersion: 'App 的 versionCode，低於這個版本就無法匯入。',
      required: '未取得這項權限就無法開始研究。',
      samplingPeriod: '這是請求而非上限，裝置可能給得比要求的更快。',
      bandwidthEstimates: '平台的估計值，不是實測值。',
      pollInterval: '一分鐘是試跑用的設定，不是正式研究的設定。',
      fastestInterval: '不能長於定位間隔。',
      batchDelay: '設得越大批次越多、越省電。',
      priority: '兩種都需要精確位置，差別只在耗電與精準度的取捨。',
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
      records: '連線類型、是否驗證、是否計費、是否漫遊，以及選用的頻寬估計值',
      limit: '不含 SSID、位址、連線對象或內容'
    },
    'network_usage.v1': {
      name: '流量',
      records: '以明確的時間區間，記錄各類連線的裝置總位元組與封包數',
      limit: '無法分到個別 App，也看不出流量發生的確切時間'
    },
    'usage_events.v1': {
      name: 'App 與螢幕使用',
      records: 'App 前景切換、螢幕、鎖定畫面與開關機事件，含套件名稱',
      limit: '會延遲也會遺漏，不是完整的使用階段序列'
    },
    'location.v1': {
      name: '位置',
      records: 'Fused Location 定位點，含精確度、速度、高度與方位',
      limit: '是取樣的估計值且會有缺口，不是連續軌跡'
    },
    'keyboard_touch.v1': {
      name: '鍵盤觸控',
      records: '僅在研究鍵盤內，記錄按鍵內的相對位置、時間、力道、觸控面積與按鍵類別',
      limit: '不含文字、不含按了哪個鍵，力道也沒有實體單位'
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
    lede: '金鑰、設定、簽章，都在這個分頁裡完成。不必安裝任何東西，也不會有資料離開瀏覽器。',
    how: {
      file: {
        title: '研究就是一個檔案',
        body: '你描述要收集什麼，用自己的金鑰簽署，再把檔案交給參與者。任何一版 App 都能執行它——不必自己寫 App，也沒有東西要部署。'
      },
      keys: {
        title: '兩把金鑰，兩種用途',
        body: '一把用來簽署研究，讓手機能確認檔案未被竄改；另一把用來解密收回來的資料。兩把的公鑰都會寫進檔案裡，私鑰則留在你手上。簽章金鑰遺失，就無法再用同一組 key ID 發出新檔案；匯出金鑰遺失，用它加密的資料就永遠打不開。'
      },
      local: {
        title: '資料不會離開這個分頁',
        body: '金鑰在這裡產生，也只留在這裡，也就是說沒有任何備份。關掉分頁前先下載私鑰，再移到你真正掌控得住的地方。'
      },
      fingerprint: {
        title: '公布金鑰指紋',
        body: '簽章只能證明檔案自簽署後未被更動，不能證明是誰寫的。請把金鑰指紋放進招募資料裡；同意畫面會顯示同樣的八組字元，讓參與者拿來核對。'
      },
      disclosure: {
        title: '資料說明由 App 撰寫',
        body: '在徵求同意之前，App 會列出你啟用的每一個收集器，用它自己的說法描述，並填入你設定的參數。同意書摘要要寫得與那個畫面一致——這是唯一你無法說得比較輕描淡寫的地方。'
      }
    },
    keys: {
      signing: {
        title: '研究簽章金鑰',
        algorithm: 'Ed25519',
        role: '用來簽署設定檔。',
        risk: '遺失：無法再用這組 key ID 發出新檔案。外洩：任何人都能冒用你的身分簽署，而且沒有撤銷機制。'
      },
      export: {
        title: '匯出加密金鑰',
        algorithm: 'X25519 · HPKE',
        role: '用來解密所有匯出與自動傳送的資料。',
        risk: '遺失：所有資料都解不開。沒有代管、沒有復原途徑，裝置也無法重新加密。'
      },
      handling:
        '下載下來的私鑰就是一般檔案。請移到你受管控的環境保存，不要放進 Git、通訊軟體或設定檔裡。',
      replace: '重新產生會捨棄目前這組金鑰，請先下載。'
    },
    study: {
      section: {
        identity: {
          title: '識別碼',
          note: '一個實驗可以有多份設定檔。下面的內容只要有任何更動，設定檔 ID 就要跟著換。'
        },
        validity: {
          title: '有效期間',
          note: '驗證時必須落在這個區間內。區間不要開太長——發出去的檔案無法撤銷。'
        },
        about: {
          title: '研究內容',
          note: '這裡的每個字都會被簽進檔案，並以原文顯示，不會被翻譯。要跨語言招募，就需要兩份各自簽署的設定檔。'
        },
        consent: {
          title: '知情同意',
          note: '這裡改了，就需要新的設定檔 ID、重新簽署，並重新取得同意。'
        },
        collectors: {
          title: '資料來源',
          note: '來源越少、頻率取夠用就好、時間越短越好。這是別人自己手機上的空間與電力。'
        },
        prompts: {
          title: '提示通知',
          note: '排程並不精確。不要設計成必須在某一分鐘準時送達的流程。'
        },
        storage: {
          title: '儲存空間',
          note: '8 MiB 到 8 GiB。空間用完時，研究會停止收集，而不是把事件丟掉。'
        },
        delivery: {
          title: '自動傳送',
          note: '關閉時，資料在參與者自行匯出前不會離開手機。開啟時，參與者無法只拒絕傳送而繼續參與——這件事必須寫進同意書。'
        }
      }
    },
    sign: {
      canonical: '正規化 JSON',
      size: ({ bytes, max }) => `${number.format(bytes)} / ${number.format(max)} 位元組`,
      blocked: (count) => `還有 ${number.format(count)} 個問題要處理`,
      publish: '請把這串字元公布在招募資料裡，參與者會拿它來核對。'
    },
    files: {
      keep: '私鑰存放在你掌控得住的地方。',
      archive:
        'researcher-tools decrypt --config 需要這個檔案，而且沒有任何指令能從 .adccfg 取回它。少了它，研究結束時你將無法讀取自己的資料。',
      publish: '金鑰指紋放進招募資料。',
      distribute: '把 .adccfg 交給參與者。',
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
