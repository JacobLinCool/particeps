/**
 * Traditional Chinese (Taiwan). Kept key-for-key with `en.ts` — `tests/i18n.spec.ts` fails if one
 * file grows a key the other lacks, because a missing key here would silently render nothing.
 */

import type { Messages } from './types';

const number = new Intl.NumberFormat('zh-TW');

export const zhTW: Messages = {
  app: {
    /**
     * 產品名稱不翻譯，也不音譯。這一個字串就是 Android 在系統設定的應用程式清單裡顯示的名稱，
     * 也是參與者拿來跟招募說明、同意書上的名字對照的那個字；只要兩邊長得不一樣，對照就失效了。
     */
    name: 'Particeps',
    tagline: '不必自行開發 App，就能進行研究。',
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
    open: '開啟',
    back: '上一步',
    next: '下一步',
    confirmSaved: '檔案已存好',
    skip: '跳到主要內容',
    startOver: '重新開始',
    confirm: '確認',
    cancel: '取消'
  },

  intervention: {
    // 「研究活動」是 App 通知頻道用的字（intervention_channel），也就是參與者在系統設定裡看得到的
    // 那個字。網站沿用同一個詞，而不是另外造一個只有研究者看得到的說法。
    title: '研究活動與問卷',
    one: '研究活動',
    empty: '沒有排定的活動。',
    notificationTiming: 'Android 會盡量按照排程發出通知，但時間可能略有誤差。',
    anonymous: '未指定參與者代碼',
    personalized: '指定參與者代碼',
    assignedId: '指定參與者代碼',
    addNotification: '新增通知',
    addSurvey: '新增問卷',
    addQuestion: '新增題目',
    addTrigger: '新增排程',
    addWindow: '新增時段',
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
    options: '選項（固定 ID | 標籤，每行一項）',
    selectionBounds: '最少／最多選項數',
    notificationTitle: '通知標題',
    notificationMessage: '通知訊息',
    trigger: '排程 ID',
    scheduleType: '排程類型',
    clock: '計時方式',
    offset: '延後時間（分鐘）',
    interval: '間隔時間（分鐘）',
    localTime: '當地時間',
    windowStart: '時段開始',
    windowEnd: '時段結束',
    occurrencesPerWindow: '每個時段提示次數',
    dailyMaximum: '每日提示上限',
    totalMaximum: '整項研究提示上限',
    minimumSeparation: '最短間隔（分鐘）',
    randomWindowSummary: ({ minimum, maximum }) =>
      `可能提示 ${number.format(minimum)}–${number.format(maximum)} 次；確切時間由手機抽選並保存。`,
    availability: '可填寫時間（分鐘）',
    types: { shortText: '簡短文字', scale: '數字量尺', singleChoice: '單選', multipleChoice: '複選' },
    schedules: {
      oneTime: '單次',
      interval: '固定間隔',
      dailyLocal: '每天的當地時間',
      randomWindow: '隨機當地時段'
    },
    clocks: { calendar: '日曆時間（暫停期間仍計時）', active: '實際收集時間（暫停期間不計時）' }
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
    files: '檔案',
    read: '讀取'
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
    millimetres: '公釐',
    lux: 'lux',
    mebibytes: 'MiB',
    bytes: '位元組'
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
      changeThreshold: '變化門檻',
      minimumEventInterval: '最短事件間隔',
      upload: '自動傳送',
      endpoint: '接收端點',
      uploadInterval: '傳送間隔',
      allowMetered: '允許行動網路',
      signerKeyId: '簽章金鑰 ID',
      signerPublicKey: '簽章公鑰',
      exportKeyId: '匯出資料的金鑰 ID',
      exportPublicKey: '匯出資料的公鑰',
      fingerprint: '金鑰指紋'
    },
    /**
     * 這裡不能宣稱、也不能暗示耗電或耗能。專案沒有量過，提示文字也不是用來猜的地方。量得出來的
     * 是位元組與時數，那些由儲存空間的量表來說。
     */
    hint: {
      override: '留空就會使用上面那組 ID。',
      contact: '請填寫參與者確實能聯絡到你的方式。',
      expiresAt: '這天之後就不能再加入了。',
      duration: '從參與者開始的那天算起。',
      consentSummary: '資料、目的、時長、風險、權限、匯出、退出、刪除、聯絡方式。',
      storageQuota: '空間用完後會停止收集，但不會刪除已收集的資料。',
      required: '沒有這項權限就無法開始。',
      samplingPeriod: '這是取樣週期要求，不是頻率上限；裝置可能更快。',
      ambientLightSamplingPeriod:
        '這是相鄰輸出事件之間的硬性最短間隔；最新一筆達到變化門檻的讀值會保留到下一次可輸出時。',
      bandwidthEstimates: '平台的估計值，不是實測值。',
      pollInterval: '一分鐘是試跑用的設定，不是正式研究的設定。',
      fastestInterval: '不能比定位間隔長。',
      batchDelay: '設得越大，傳送次數越少，每次傳送的資料越多。',
      priority: '兩種模式都需要精確位置。高精確度會用 GPS。',
      endpoint: '必須是 https，而且由你自己營運。',
      allowMetered: '關閉時只會使用 Wi-Fi。行動網路可能產生費用，應先取得參與者同意。'
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
      name: '手機移動',
      records: '裝置座標系中的原始 x、y、z 軸加速度，包含重力',
      limit: '不含動作、姿勢或手勢標記'
    },
    'battery_state.v1': {
      name: '電池狀態',
      records: '電量百分比、充電狀態與來源，以及省電模式狀態',
      limit: '不含序號、硬體 ID、健康狀態或溫度'
    },
    'temporal_context.v1': {
      name: '時間脈絡',
      records: '時區 ID、UTC 偏移、日光節約狀態與時間變更原因',
      limit: '不把時區當作位置或旅行紀錄'
    },
    'gyroscope.v1': {
      name: '旋轉',
      records: '原始 x、y、z 軸角速度與感測器準確度',
      limit: '不推論方向、活動、姿勢或手勢'
    },
    'ambient_light.v1': {
      name: '環境光',
      records: '原始照度、感測器時間與準確度',
      limit: '不含影像或環境內容，也不推論人在不在場'
    },
    'proximity.v1': {
      name: '距離感測',
      records: '原始距離、最大範圍與遠近判定',
      limit: '許多手機只能回報遠近；不同裝置的數值不可直接比較'
    },
    'network_state.v1': {
      name: '連線類型',
      records: '連線類型、是否按流量計費，以及漫遊與驗證狀態',
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
      limit: '定位結果是取樣估計值，可能有缺漏，無法形成連續軌跡'
    },
    'keyboard_touch.v1': {
      name: '鍵盤觸控',
      records: '按鍵內的相對位置、時間、力道與觸控面積',
      limit: '僅記錄研究鍵盤內的觸控，不含文字或按鍵內容。'
    }
  },

  issue: {
    required: '必填',
    id_format: '小寫英數字與連字號，3 至 64 個字元',
    length_range: ({ min, max }) => `${number.format(min)} 至 ${number.format(max)} 個字元`,
    number_range: ({ min, max }) => `須介於 ${number.format(min)} 與 ${number.format(max)} 之間`,
    integer: '只能是整數',
    instant: '無法辨識這個日期時間',
    window_order: '要比開始時間晚',
    collectors_empty: '至少要啟用一個資料來源',
    duplicate_id: '這個研究裡已經用過',
    transports_empty: '至少選一項',
    location_interval_order: '不能超過定位間隔',
    endpoint_scheme: '必須以 https:// 開頭',
    endpoint_host: '這個位址沒有主機名稱',
    document_too_large: ({ max }) => `整份設定檔必須小於 ${number.format(max)} 個位元組`,
    signer_missing: '請先產生簽章金鑰',
    export_key_missing: '請先產生匯出資料的加密金鑰',
    key_invalid: '這不是 Protocol v1 的 32-byte 標準公開金鑰。請重新產生或匯入金鑰',
    language_tag: '請使用有效的 BCP 47 語言標籤',
    unknown_reference: '請選擇這份設定檔中已定義的問卷',
    selection_bounds: '選取數量限制與這題不相容',
    schedule_bounds: '排程超出研究期間，或產生過多次活動'
  },

  status: {
    copied: '已複製',
    verified: '簽章已通過驗證',
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
    draft: '這不是有效的研究設定檔，無法匯入。',
    keyFile: '這不是有效的私鑰檔案，無法匯入。',
    clipboard: '複製失敗，請自己選取文字複製。',
    notFound: '找不到這個頁面。',
    bundle: {
      not_a_bundle: '這不是有效的匯出檔，無法讀取。',
      too_large: '這個檔案太大，這個分頁開不起來。',
      wrong_study: '這個匯出檔屬於別的研究，請改用那個研究的設定檔。',
      wrong_key: '這把私鑰打不開這個研究的資料。',
      unwrap_failed: '封裝這個匯出檔時用的不是這份設定檔。',
      tag_failed: '這個匯出檔在手機寫出後被改過。',
      unreadable: '解密成功，但內容不是這個頁面讀得懂的格式。'
    }
  },

  confirm: {
    startOver: {
      title: '捨棄這個分頁裡的所有內容？',
      body: '金鑰與設定檔只存在於這個分頁，沒有其他備份。尚未下載的內容都會消失。'
    },
    leave: { title: '金鑰還沒存檔，確定要離開？' },
    replaceKey: {
      title: '要換掉這個分頁裡的金鑰嗎？',
      body: '會捨棄這個分頁裡的金鑰組，改用新產生的一組。已經簽好的檔案仍然屬於原本的金鑰 ID。'
    }
  },

  researcher: {
    title: '準備一份研究',
    lede: '金鑰、設定檔與簽章都只保留在這個分頁。',
    how: {
      file: {
        title: '研究就是一個檔案',
        body: '你描述要收集的內容，以自己的金鑰簽署，再把設定檔交給參與者。同一個 App 就能執行不同研究的設定檔，不必另外開發或部署 App。'
      },
      keys: {
        title: '兩把金鑰，兩種用途',
        body: '一把用來簽署研究設定檔，另一把用來解密收到的資料。'
      },
      local: {
        title: '所有內容都只保留在這個分頁',
        body: '這裡沒有任何備份，關閉分頁前請先下載金鑰。'
      },
      fingerprint: {
        title: '公布金鑰指紋',
        body: '簽章只能證明設定檔在簽署後未被修改，無法證明簽署者的真實身分。請將金鑰指紋放入招募資料。同意畫面會顯示相同的八組字元，供參與者核對。'
      },
      disclosure: {
        title: '資料說明由 App 提供',
        body: '取得參與者同意前，App 會說明你啟用的每一個資料來源。'
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
        risk: '遺失後須重新產生。'
      },
      export: {
        title: '匯出資料的加密金鑰',
        algorithm: 'X25519 · HPKE',
        /** 全站最窄的位置：檔案步驟的下載磚裡，那一條只有 135px。 */
        risk: '遺失即無法解密。'
      },
      /** 檔案步驟裡「妥善保存金鑰」那一欄的提示，寬度 218px。全站唯一一句講外洩的話，
       *  放在歸檔的地方，而不是產生金鑰的地方。 */
      handling: '請勿放進 Git 或通訊軟體。',
      /** 收在預設關閉的摺疊區裡：沒有人被要求自備金鑰，但不代表不能。
       *  會這麼做的理由只有 reuseNote 那一句。 */
      reuse: '改用我已經有的金鑰',
      reuseNote: '重複使用同一把金鑰時，指紋也不會改變。'
    },
    study: {
      /** 五個標題，一句說明。標題底下的控制項已經說明白這一段在做什麼，
       *  只有 delivery 要補一件畫面上看不到的事。 */
      section: {
        about: { title: '研究內容' },
        validity: { title: '這個研究會進行多久？' },
        identity: { title: '這份檔案要發給誰？' },
        collectors: { title: '資料來源' },
        consent: { title: '知情同意' },
        interventions: { title: '研究活動' },
        delivery: {
          title: '自動傳送',
          note: '未啟用自動傳送時，資料只會在參與者自行匯出後離開手機。'
        }
      },
      /** 一句一行，放在它所說明的那個控制項旁邊。 */
      note: {
        irrevocable: '已交付的設定檔無法收回。',
        /** 放在七張卡片上面，只說一次。每張卡片上的那顆按鈕都是同一個決定，
         *  決定帶來的後果也一樣，不必在卡片裡重複七遍。 */
        required: '標成「必要」的來源，參與者不同意就不能開始。',
        disclosure: '同意書摘要必須與 App 顯示的資料說明一致。',
        delivery: '參與者無法只拒絕傳送而繼續參與。請在同意書中說明清楚。'
      }
    },
    sign: {
      identity: {
        title: '識別碼',
        /** 現在是四列不是兩列：研究、設定檔，以及兩把金鑰。每一組都是推導出來的。 */
        note: '這些 ID 會隨資料一併傳送，分析時必須使用。'
      },
      canonical: '正規化 JSON',
      size: ({ bytes, max }) => `${number.format(bytes)} / ${number.format(max)} 位元組`,
      blocked: (count) => `還有 ${number.format(count)} 個問題要處理`,
      publish: '請將這組金鑰指紋公布在招募資料中，供參與者核對。'
    },
    files: {
      keep: '妥善保存金鑰',
      /** 218px。「沒有任何指令能從 .partcfg 取回它」放不下，留在研究者指南裡；這裡只放會影響決定的後果。 */
      archive: '解密收到的資料時需要使用。',
      publish: '請將金鑰指紋放入招募資料。',
      distribute: '給參與者',
      pilot: '正式招募前，請先在研究預計支援的 Android 版本與機型上完成測試。',
      join: {
        title: '選用的加入連結與 QR Code',
        artifactUrl: '已簽署 .partcfg 的 HTTPS 位址',
        artifactHint: '請先託管完全相同的已簽署檔案，再輸入最終位址；App 不接受重新導向。',
        personalizedHint: '最後一段路徑必須是夠長的隨機字串，且位址不得包含指定參與者 ID。',
        copy: '複製加入連結',
        invalid: '請輸入有效的最終 HTTPS 位址。個人化檔案須使用不洩露指定參與者 ID 的長隨機路徑。',
        immutable: 'QR Code 完全在本機產生，並綁定此檔案的完整 SHA-256 與簽章指紋。App 只下載一次，不會輪詢更新。',
        qrAlt: '不可變研究加入連結的 QR Code'
      }
    },
    read: {
      lede: '開啟參與者回傳的匯出檔。所有內容都只保留在這個分頁。',
      bundle: '加密的匯出檔',
      session: '用這個分頁裡的',
      opened: '已在這個分頁開啟',
      events: '事件筆數',
      window: '序號範圍',
      span: '首末事件時間',
      transitions: '狀態變更',
      exported: '匯出時間',
      instance: '參與者安裝識別碼',
      state: '目前狀態',
      json: '解密後的 JSON',
      large: '內容太長，這裡不顯示，請下載檔案閱讀。',
      none: '這個匯出檔沒有任何事件。'
    },
    cli: 'researcher-tools 可在終端機完成相同流程，也能解密收到的資料。'
  },

  participant: {
    title: '在你同意之前',
    lede: '同意之前，先了解這個 App 會在手機上做什麼、要求你提供什麼，再決定是否參與。',
    how: {
      start: {
        title: '按下「開始」前不會收集任何資料',
        body: '即使匯入研究團隊提供的設定檔，App 也不會立即開始收集。設定流程共有五個畫面，你可以隨時停止。拒絕參與不需要說明理由。'
      },
      list: {
        title: '同意之前，你會先看到清單',
        body: '其中一個畫面會列出這個研究啟用的所有資料來源及取樣頻率。說明文字由 App 提供，研究團隊無法透過設定檔修改。'
      },
      storage: {
        title: '資料留在你的手機上',
        body: '資料寫入時就會加密。你可以自行匯出後交給研究團隊。若研究設有自動傳送，App 也會依排程傳送，並在同意畫面事先說明。'
      },
      fingerprint: {
        title: '核對金鑰指紋',
        body: '同意畫面會顯示八組、每組四個字元的金鑰指紋。請與研究團隊先前公布在可信管道中的指紋逐組核對。簽章只能證明檔案未遭竄改，核對指紋才能確認簽章金鑰是否屬於研究團隊。如果指紋不符，或研究團隊從未提供指紋，請先詢問清楚再同意。'
      },
      control: {
        title: '你隨時可以停止',
        body: '暫停、提早完成或退出研究都不需要說明理由，App 也不會詢問。停止後仍可匯出已收集的資料，或刪除手機上的全部本機資料。如果資料已離開手機，則必須聯絡研究團隊處理。'
      }
    },
    flow: {
      study: {
        title: '研究',
        body: '研究名稱、研究團隊、聯絡方式與研究期間。'
      },
      data: {
        title: '資料',
        body: '研究啟用的資料來源及各自的取樣頻率。'
      },
      consent: {
        title: '同意',
        body: '研究團隊提供的說明、簽章指紋，以及研究是否會自動傳送資料。'
      },
      access: {
        title: '權限',
        body: '這些資料來源需要 Android 權限。標示為選用的權限可以不授予。'
      },
      start: {
        title: '開始',
        body: '按下「開始」後才會收集資料。'
      }
    },
    note: '這個頁面說明 App 的行為。如果內容與你參與研究的同意書不一致，請以同意書為準。',
    closing:
      '如果手機顯示的內容與研究團隊先前的說明不一致，請先停止並詢問清楚。聯絡時請使用你原本就有的聯絡方式，不要使用研究畫面中顯示的資訊。'
  },

  link: {
    researcherGuide: '研究者指南',
    participantGuide: '參與者指南',
    threatModel: '威脅模型',
    source: '原始碼'
  }
};
