# 120 小時研究的耗電與執行模型評估

評估日期：2026-09-07。範圍為目前工作目錄的 Android App、collector、runtime、加密儲存、原生 VPN、排程與上傳設計，包含尚未提交的五天研究支援。本報告是原始碼與 Android 平台契約的評估，沒有實機功耗量測，優先順序不是已量得的耗電占比。

## 本次實作結果（2026-09-07）

以下原始碼評估描述修改前的基線。現已完成四項改善：

- runtime 的已提交狀態包含完整的時間與參與者投影欄位；應用層正常更新不再呼叫 `loadRuntime()`。投影更新與研究切換使用同一把 session lock，避免舊研究覆寫新狀態。
- 每筆 commit 仍先同步落盤；完整加密快照改為累積 64 筆 commit 或 1 MiB frame bytes 時寫入。生命週期、安全停止、恢復、epoch 變更及 pending consumption 會要求快照；既有 eviction 邊界保留。快照寫入失敗後持續重試，直到成功確認。沒有增加計時喚醒。
- 配額查核以所有 base／pending／replacement／segment 的有效一般檔案大小計算，不再為取得大小而讀取整份密文。符號連結與非一般檔案明確拒絕。
- admission 等待從 1 ms 輪詢改成 mutex／drain 訊號競爭。取消、drain 與鎖交接都會清理自身所有權，取得鎖後仍重新檢查 admission。

此外，修正中斷快照留下多個內容相同候選檔時的恢復判定；內容衝突仍拒絕。沒有修改 120 小時、限速時間、500 kbps、所有 App 範圍、gyro 抽樣頻率、wake lock、問卷或輪詢間隔。感測器 FIFO、跨來源批次提交與 VPN 處理路徑優化尚未實作。

驗證包含：禁止加入研究後的投影呼叫 storage recovery、100 次 pause／resume 的同 revision 欄位一致性、沒有輪詢計時器的鎖等待與取消競態、快照額度／失敗重試、配額查核不讀密文，以及 Android Keystore 實際加密資料在未更新快照、尾端截斷、checkpoint 中斷及密文損壞時的恢復。這些驗證證明行為及工作量路徑已改變，不是電池節省百分比量測。 本次 119 項 JVM 測試及 API 34 模擬器 22 項儲存測試通過，Android lint 與 debug APK 建置成功。

## 結論

保留 collector 插件、單一事件序列、純函式 automation reducer、持久化後執行副作用與必要來源失效時暫停的設計。需要重整的是資料路徑的工作頻率：目前把高頻觀測、持久化、完整狀態恢復及顯示更新連在一起；此外，陀螺儀要求 CPU 在研究進行期間保持喚醒。

先修正正常更新反覆重播歷史資料，以及每筆 commit 都重寫快照的成本。再實作經硬體能力驗證的感測批次與有限度的寫入合併。最後才調整輪詢與 VPN 排程。不要用關閉熄屏收集、縮短研究時間或悄悄降低抽樣頻率來替代架構改善。

## 1. 正常狀態更新呼叫完整儲存恢復：最高優先

`StudyApplication.kt:973` 的 `observeRuntimeLocked()` 訂閱每次 runtime snapshot，並於第 978 行呼叫 `store.loadRuntime()`。此訂閱屬於應用層 scope，不需要 Activity 顯示。

`EncryptedExperimentStore.kt:82` 的 `loadRuntime()` 沒有直接回傳記憶體狀態，而是讀取／解密 snapshot，再呼叫 `replayAfter()`。第 469 行開始的重播會從 `retainedFromCommit` 讀取、解密並驗證保留的 commit 鏈；即使 snapshot 已是最新，仍會掃描保留的歷史。

因此，一次普通感測資料提交會導致顯示投影執行恢復工作。StateFlow 會合併未及處理的更新，所以不能假定每筆 commit 都有一次完整重播；但每次實際觀察到的更新仍會處理歷史，並與寫入共用 storage mutex。若每次更新皆被處理且歷史持續保留，累積工作量呈平方成長。這也會拖慢提交、增加 callback queue 壓力，最終可能形成資料缺口。

**修改方向：**

- 由 runtime 在 durable append 成功後發布不可變、同一 revision 的狀態投影，直接包含 participant projection 需要的開始／截止／時鐘資訊。
- 正常觀察不得呼叫磁碟 recovery API，也不得拼接不同 revision 的 runtime 與 document。
- 完整重播保留在初始化／明確恢復／稽核；不能以未驗證磁碟內容充當記憶體狀態。
- 只有畫面可見時，才節流一般計數顯示；暫停、權限失效與截止等狀態轉換仍立即發布。

**驗收：**一般更新的 storage recovery 呼叫數為零；同樣的更新在一小時與五天歷史量下，不因歷史長度增加讀檔量；程序重啟的完整性驗證與安全暫停行為維持不變。

## 2. 一個感測事件對應一筆 commit，外加完整快照

`SerializedCallbackCollector.kt:176–189` 雖呼叫 `emitBatch`，每次實際只傳入 `listOf(message.draft)`。runtime 對這筆 submission 執行 reducer、建立 checkpoint／digest、提交 commit，並發布 snapshot。

`EncryptedExperimentStore.kt:286–333` 的成功提交路徑包含：

1. 編碼、驗證、加密 commit。
2. append frame 並同步檔案；`appendFrameDurably()` 在第 915 行。
3. 再編碼、加密整份 runtime snapshot，透過 `AcknowledgedAtomicFile` 寫入。

`AcknowledgedAtomicFile.kt:81–110` 的一般快照寫入需要兩份 staged file 的檔案同步、三次目錄同步，並讀回驗證。加上 commit log，同一筆提交通常涉及六次同步呼叫，尚未包含建立新 segment 等工作。這是程式呼叫數，不能等同六次獨立實體寫入或六次 CPU 從休眠喚醒。

以「實際收到 1 Hz」為假設，120 小時有 432,000 筆 gyro 事件，僅此來源就可能造成約 259 萬次同步呼叫。Android 的 sampling period 是請求提示，實際事件頻率可能不同，這不是實測數量。

另外，`storageBytes():800` 在每次提交時使用 `snapshotFile.candidates()` 讀取完整候選檔來計算大小，並列舉 segment。配額查核也被放進高頻路徑。

**先做不改變事件持久性語義的改善：**

- 保留每筆 commit 的 durable acknowledgement；快照改為每累積一定 commit 數或 bytes 時重建，並在必要生命週期／儲存邊界落盤。起始快照必須持久存在，恢復仍驗證完整保留鏈。
- snapshot 是恢復快取，不必與每筆資料具有相同更新頻率。可先用 60 秒等級加上筆數／大小上限作為測試起點，閒置時不必為了快照單獨喚醒。
- 以明確的 file-size metadata API 與經初始化核對的計數做配額查核；輪替、失敗復原及刪除都須同步更新，不能使用未核對的估值。

**後續寫入批次設計：**

- 區分「硬體批次回報」、「collector 多筆提交」、「多個邏輯 commit 合併一次同步」；三者各自解決不同成本。
- 維持有界 queue、來源順序、原始 timestamp、producer ordinal、resource generation 與 condition epoch。只有同步成功才回覆已持久化。
- 若增加記憶體等待時間，程序死亡可能損失尚未落盤的資料；需明訂最大資料年齡／筆數與品質缺口，不得把排入 queue 當成 durable acceptance。
- 暫停、撤回、截止、資源切換需要 barrier；不能讓一批資料穿過不同 epoch，或延後需要即時執行的控制事件。

**驗收：**量測每千筆事件的檔案同步次數、加密次數、磁碟 bytes、queue 峰值、commit 延遲與 CPU 時間。加入斷電／程序死亡、快照落後、尾端截斷與配額邊界測試。

## 3. 陀螺儀長時間持有 partial wake lock

`GyroscopeCollector.kt:49` 固定 `keepCpuAwake = true`；`AndroidSensorCollector.kt:70` 註冊時取得 wake lock，直到暫停、停止或回滾才釋放。五天範本的 `maximum_report_latency_us` 為零。

這個設計是為了讓一般 non-wake-up gyro 在熄屏時持續交付資料，但會阻止一般 CPU suspend 路徑。降低 gyro 抽樣頻率不會解除這個 wake lock。Android 官方也指出 non-wake-up sensor 在 AP suspend 時可能失去事件；不能直接移除鎖再聲稱完整收集。參見 [SensorManager 契約](https://developer.android.com/reference/android/hardware/SensorManager)。

**目標設計：硬體持續抽樣，CPU 批次處理。**

- admission 前檢查 wake-up gyro、FIFO 容量、reserved count、實際支援的頻率與回報模式；將能力與選用模式保留為研究診斷資料。
- 對經驗證支援的機型，使用 wake-up gyro 加上合理的正值 `maxReportLatencyUs`，例如先評估 30 秒。FIFO 空間需要涵蓋實際頻率 × 等待時間及喚醒餘裕，不能僅以最大共享容量推定保證。
- 以原始 sensor timestamp 保存每個 sample，不因批次而改成平均值、callback 時間或丟掉中間 sample。
- 控制轉換時需 `SensorManager.flush()` 與 `SensorEventListener2.onFlushCompleted()` 等可驗證的排空流程，並正確處理尚在硬體 FIFO 的事件。現有 listener 只有 `SensorEventListener`，因此目前不宜只把 latency 改成 30 秒。
- 不支援所需 wake-up／FIFO 契約的機型，必須明確選擇目前較耗電的連續模式，或不納入相同研究配置。不可靜默降級成可能漏資料的模式。

Android 說明：non-wake-up FIFO 在 suspend 時可能覆寫舊事件，`max_report_latency` 不能保證在此狀態喚醒；wake-up FIFO 則會在滿載或期限時喚醒 AP。[AOSP batching](https://source.android.com/docs/core/interaction/sensors/batching)

**驗收：**在每個目標 OEM 機型以長時間熄屏測量 suspend residency、wake lock 時間、實際 sample 間距、FIFO flush 完成及跨時段歸屬。不能用模擬器 gyro 測試推定感測器中樞的功耗或 FIFO 保證。

## 4. 輪詢與排程：減少彼此獨立的工作節奏

目前五天配置／程式中的名目頻率如下。數量按完整運行 432,000 秒推算；執行延遲與暫停會改變實際次數，亦不等同硬體 wakeup 次數。

| 工作 | 目前間隔 | 120 小時名目次數 | 建議 |
| --- | ---: | ---: | --- |
| 被動 throughput | 10 秒 | 43,200 | 若 30 秒平均速率足夠，可降為 14,400 次；保留真實 interval 起訖 |
| UsageEvents 查詢 | 15 秒 | 28,800 | 本研究沒有以 App 使用觸發限速，可評估 60 秒查詢，保留原始事件時間與邊界 flush |
| 特殊存取查核 | 25 秒 | 17,280 | 整合可靠 callback 與定期核對；變更間隔會影響撤銷偵測延遲，不宜任意拉長 |
| VPN 稽核 | 60 秒 | 7,200 | 保留健康 callback；合併非緊急稽核排程並減少 WorkManager job churn |

`AndroidTimerWakeupAdapter.schedule():239` 為 durable timer 建立一次性 WorkManager 工作；VPN 稽核每次觸發又退休舊 timer、產生新 timer。應把控制邊界與可延遲的遙測分開處理：控制邊界保有 durable generation 與重啟恢復，背景遙測可在服務存活期間共用批次處理時機，並維持持久化 outbox／稽核語義。

沒有必要把每一個 sample 做成 WorkManager 工作。反之，也不能把 12:00／17:00 的控制邊界只交給普通 Handler。`Handler.postDelayed` 使用 uptime，deep sleep 會延後 callback；移除長期 wake lock 後必須重新測試所有時間邊界。[Android SystemClock](https://developer.android.com/reference/android/os/SystemClock)

UsageEvents 查詢間隔與事件 timestamp 精度是不同概念；較慢查詢主要增加交付延遲，但不能假定 Android 永遠提供無缺漏歷史。批次大小、查詢區間、鎖屏、權限失效與 collector 契約必須一併驗證。throughput 間隔變大則確實降低速率時間解析度，因此這兩項仍是研究設定決策，尚未套用到原範本。

runtime 等候 mutex 時另有 1 ms 重試（`ExperimentRuntime.kt:1022,4025`）。在儲存壅塞下可能放大排程成本；應改為可取消的鎖／drain 通知等待，且保留 gate 與 barrier 的競態保證。先消除歷史重播與寫入壅塞，再依 trace 決定此項順位。

## 5. 全 App VPN 是固定的處理成本

五天範本讓 unlimited baseline VPN 全程存在，僅在限速時段套用 500 kbps。這有助於讓比較期間保持相同 VPN 路徑，但 unlimited 不代表原生引擎停工。

`native/traffic-shaping/shaped_tun.go:27,62` 對每個 TUN packet 執行 gate、limiter 與計數，再經使用者空間網路堆疊與 socket 轉送；unlimited 分支只略過等待額度。使用者流量較大時，這條路徑的 CPU 成本可能重要，需分別量測 idle、一般流量與飽和傳輸。

優化應先量測 packet／syscall 頻率、配置與 GC、複製、lock contention、處理延遲；不要在未分析前重寫網路引擎。現有 blocking TUN I/O 與可取消等待值得保留，未發現需要靠忙迴圈等待封包的設計。限速本身可能延長傳輸及 radio 活躍時間，這是研究處置效果的一部分，須和 App 的額外處理成本區分。

只在每天五小時開啟 VPN 能減少路徑存在時間，但會改變原本的實驗比較條件，也需調整 runtime 對 required actuator 的約束。此方案列為研究設計選項，不直接套用。

## 6. 已合理的部分

- screen、network state、VPN state 採 Android callback，無須再增加高頻輪詢。
- throughput 讀取被動計數，沒有額外測速網路請求。
- 上傳已有網路限制、低電量限制與退避；五天範本關閉自動上傳，不是本配置首要耗電來源。
- 純 reducer 與持久化副作用有助於離線重播驗證；不需要為省電移除加密、稽核或安全停止。
- 前景服務的角色是維持合法背景工作與使用者可見性，不能把「前景服務存在」直接當成主要耗電原因；要查實際 wake lock、CPU、磁碟與網路活動。

## 建議實作順序與驗證

| 階段 | 交付內容 | 對研究資料的影響 |
| --- | --- | --- |
| A | 分離正常狀態投影與磁碟恢復；量測歷史量增加時的讀檔／CPU | 可保留目前資料與排程語義，優先做 |
| B | 降低快照更新頻率、改善配額統計；保持 commit 的持久性 | 可保留已回覆資料的耐久性；驗證重啟成本 |
| C | 硬體能力契約、gyro FIFO flush、批次提交與 epoch 邊界 | 保留原始 sample，但改變交付延遲；須實機驗證 |
| D | 共用非緊急工作節奏、移除 1 ms lock 重試、調整顯示更新 | 保留控制邊界與撤銷處理時限 |
| E | 依 trace 優化 VPN packet path；評估 30／60 秒研究輪詢設定 | 輪詢時間解析度及 VPN 暴露條件須明訂 |

先使用相同機型、網路、亮度、電量／溫度區間與可重現負載，分別量測：裝置閒置、現行完整研究、只做 A、A+B、經驗證的 C，以及 baseline／limited VPN 的 idle 和固定流量。各條件重複且交錯順序，避免溫度與電池狀態偏差。測試應涵蓋熄屏與亮屏，並與網路限速處置分層比較。

使用 Perfetto／System Trace 與支援機型的 Power Profiler，收集整機能量／平均功率、CPU 時間、suspend residency、wake lock duration、磁碟 I/O、同步次數、GC、queue backlog、sample coverage、控制邊界延遲。ODPM 是整機功率，不能直接標成此 App 的獨占耗電。[Android Power Profiler](https://developer.android.com/studio/profile/power-profiler)

短期 trace 找到成本後，再進行熄屏長測及完整 120 小時實機試跑。測試功耗時應處理 USB 充電、ADB 與持續 profiler 本身的干擾，並對照相同負載；不能只用電池百分比或 debug 模擬器得出節電比例。本報告不承諾任何未量測的省電百分比。
