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

## 第 2 項實作狀態（2026-09-25）：只合併已排隊的 callback

本次只實作第 2 節中不改變持久性語義的 collector 端合併，沒有實作第 3 節的 wake-up gyro、FIFO flush、`maxReportLatencyUs` 調整或暫停前排空；gyro 的 wake lock、抽樣頻率與回報延遲設定不變。

- `SerializedCallbackCollector` 的 consumer 醒來時，把當下已在佇列中的 callback 合併成一筆 `SourceEventBatch`，不等待後續 callback。沒有新增計時器、wake lock 或交付延遲；每個被接受的 observation 仍先持久化（一般 `EngineCommit`，或 barrier 期間的 pending slot），再回覆 accepted。
- 批次在以下位置分割：barrier／stop 訊息（barrier 仍在所有先前事件處理完後才完成）、admission token 不相等、observed time 倒退或 boot session 改變，以及 min(4,096、registry 每批上限、⌊1 MiB ÷ 來源最大 encoded event bytes⌋) 筆，gyro 為 512 筆。
- 合併批次被 admission gate 拒絕或違反事件契約時，以折半找出可接受的前綴；每次接受後重新提交整段剩餘事件，因為研究截止時間過後才開始的 drain 會放寬 admission。以此方式被接受的每個部分至少涵蓋剩餘可接受事件的一半，所以一批只產生 O(log n) 個被接受的 observation，限制 barrier pending slot 的重寫次數，offer 次數則為 O(log² n)。producer ordinal 只在接受時前進。單一事件被 gate 拒絕時，該事件及其後事件丟棄；單一事件違反契約時，它之前的有效事件已經記錄，collector 在該事件失效，其後事件丟棄。儲存失敗或品質缺口立即使 collector 失效。失效前已接受的部分仍保留。
- runtime 發現合併批次在第一個事件之後才改變 desired resource 時，只把改變之前的事件記錄為一般 commit，並以 `Accepted.recordedEvents` 回報筆數；collector 以下一個 producer ordinal 提交其餘事件。因此 staged 的 causal observation 從造成改變的事件開始，它之前的 callback 與逐筆提交時一樣先 commit。有 coverage 的回溯批次一律整批處理。
- 一個 commit 依 reducer 的原順序記錄 timer intent，但只保留屬於 timer map 淨變化的 intent，每個一次：退休先前 map 所持 generation、且該 timer 在結果中已被移除或取代者，記為 `TIMER_RETIRED`；排程結果 map 所持 timer、且先前 map 沒有該 timer 者，記為 `TIMER_SCHEDULED`；也只有這些 intent 交給 WorkManager。單一輸入的 intent 全部保留。合併批次中每個事件都讓 window 滑動時，不會重複記錄基準 generation 的退休，也不會為中間的 generation 排程多餘的喚醒；從 `RUNNING` 復原時，quality gap 的重設會在暫停前重新排程每個條件 timer，這個 generation 同樣不記錄也不喚醒。Python 驗證器採用相同規則。
- runtime 以 stale 拒絕的 timer 喚醒（runtime 持有該 timer 的較新 generation）直接結束，不再重試。RC13 以每次多 10 秒的 backoff 重試，第一天約 130 次，直到該 timer 下次被移除（例如下一次暫停）；RC13 每次從 `RUNNING` 復原都會為重新排程的條件 timer 留下這種喚醒。
- `LocationCollector` 將每個 `LocationResult` 以 `captureAll` 排入一則訊息，每個 fix 保留各自的 observed time。

量測是原始碼路徑的工作量，不是耗電量測。以下數字是 2026-09-25 的一次性快照：以 commit `4010b55` 加上本分支尚未提交的工作目錄為基底，設定如各項所列。量測程式與模擬器計數修補沒有納入 repository，無法從 repository 重跑；之後的 collector 或 storage 變更可能使這些數字過時，重新評估時須重新量測。

- JVM：真實 `SerializedCallbackCollector` 與 `ExperimentRuntime`，記憶體內 StudyStore。每次喚醒時已排入 50 筆：每 1,000 筆事件的 commit 從 1,000 降為 20；consumer 在 `Dispatchers.Default` 自然排程時，三次執行分別為 23、24、28。觸發事件先單獨提交、其後 49 筆排在它之後時，barrier pending slot 的寫入從 50 次降為 2 次，累計重新編碼的事件從 1,275 筆降為 51 筆。
- API 34 模擬器，P2 gyro 以 20 ms 抽樣、`maximum_report_latency_us = 0`，20 秒視窗，記憶體內 sink：sink 不延遲時，修改前後都是 1,000 筆事件對應 1,000 個 observation。每個 sample 單獨到達，佇列沒有累積，所以不會合併。
- 同一模擬器設定，由 sink 對每個 observation 假設 20 ms／100 ms 的提交延遲：修改後分別是 1,000 筆事件 880 個 observation、1,020 筆事件 196 個 observation。修改前同一視窗只處理 882／196 筆事件，每筆一個 observation，consumer 落後且積壓持續增加；依 100 ms 時的速率推算，2,048 筆佇列約 50 秒後會滿。這兩個延遲是假設值，不是加密儲存的實測延遲。

因此，在 `maximum_report_latency_us = 0` 時，只合併已排隊 callback 並不會減少一般負載下的 gyro commit；下一節的 5 秒提交窗口改變了這一點。CPU 喚醒與 wake lock 的成本仍需第 3 節經硬體驗證的批次與 flush 設計。

對研究資料的影響：原始 sample、observed time、時間解析度、交付延遲、持久性與裝置資格不變，但可能改變介入暴露。reducer 仍依序處理每個事件，每個 observation 卻只 reconcile 一次 desired resources。同一合併 observation 內先成立又復原的條件不再切換資源或輪替 epoch；逐筆提交時，排在觸發事件之後的復原事件會以 pre-drain 輸入先於觸發事件被 reduce，資源仍會切換。合併在觸發事件之前的 callback 先以一般 commit 記錄，與逐筆提交相同；觸發事件與排在其後的 callback 組成 causal observation，在 barrier 的 pre-drain 輸入之後才被 reduce。事件記錄與 condition-epoch 歸屬不變；在合併的 causal observation 內，事件序號依 capture 順序。

驗證：`SerializedCallbackCollectorTest` 涵蓋不等待的合併、barrier 不跨越且在先前事件處理後才完成、數量／位元組上限、token 不相等、時間倒退與 boot 變更、被拒批次的前綴與連續 ordinal、admission 在拒絕後放寬時仍提交剩餘事件、契約違規前的有效事件先記錄且 collector 在違規事件失效、只記錄前段時以下一個 ordinal 提交其餘事件，以及 `captureAll`；`EventAdmissionGateTest` 涵蓋 token 相等性；`AutomationReducerTest` 涵蓋第一個改變資源的輸入；`ExperimentRuntimeTest` 涵蓋合併批次中段的觸發從觸發事件開始 staged、其前事件先 commit 且與逐筆提交有相同的 epoch 歸屬、drain 期間的輸入不使 rising edge 重複觸發、同一批內先成立又復原不輪替 epoch、合併的 window 滑動只記錄 timer 淨變化且不排程中間 generation、同一批內先啟動又取消的 timer 不留下記錄或喚醒、Start 的單一輸入 commit 依 reducer 順序記錄每個 timer intent，以及從 `RUNNING` 復原時每個條件 timer 只記錄一次退休且不喚醒重新排程的 generation；`CommittedTimerIntentsTest` 涵蓋保留規則本身；`StudySessionManagerTest` 涵蓋 stale 的 timer 喚醒結束而不重試；particeps-analysis 的 `test_engine.py` 涵蓋驗證器的相同規則。

## 第 2 項後續（2026-09-25）：陀螺儀與加速度計的 5 秒提交窗口

使用者核准的取捨：程序被終止時，最多約 5 秒尚未提交的 sample 可能遺失，而且遺失必須落在恢復時記錄的品質缺口內，不能無聲發生。安全暫停與時間變更會先關閉 admission，不在這項取捨內：開啟的窗口在這些路徑上被丟棄，沒有指名該 sensor 的品質缺口（見下方「不經品質缺口記錄的遺失」）。時間變更路徑是否改為先 drain live 來源會改變 discard commit 的內容，尚待決定。本次仍沒有實作第 3 節的 wake-up gyro、FIFO flush 或暫停前排空；gyro 的 wake lock、抽樣頻率與回報延遲設定不變。

- 範圍：只有 `gyroscope.v1` 與 `accelerometer.v1` 傳入 `CallbackCommitWindow.SAMPLED_SENSOR`（5 秒，即 `MAXIMUM_CALLBACK_COMMIT_WINDOW`）。ambient light、proximity、screen、network、keyboard、location、app lifecycle、battery 等 on-change／事件來源不傳窗口，行為不變。
- 前提：簽署研究的 automation 沒有任何 matcher 引用該 sensor 的事件，而且整份研究沒有任何 sequence 或 window 狀態。`EventDrivenRuntimeAssemblyFactory` 依編譯後的 `CompiledAutomationProgram.requiresPromptCommits`（即 `referencesSource` 或 `retainsEventTimeOrderedState`）設定 `CollectorContext.requiresPromptCommits`；這是程序內的組裝狀態，不簽署、不儲存、不匯出。編譯器並不禁止引用這兩個 sensor：event_match trigger 與 event_latch 條件可以引用；sequence 與 window 因為 `PLATFORM_ONLY` 速率沒有強制上限，以 `UNBOUNDED_SOURCE` 拒絕（`AutomationCompiler.kt` 的 `validateRetainedBound`；產生的 registry 只對 `HARD` 速率設定 `rateBound`）。被引用的 sensor 不開窗口，每個 sample 照舊提交。研究只要在 trigger、guard 或 resource binding 的任何位置有 sequence、window-threshold trigger 或 window-threshold 條件，不論選的是哪個來源，兩個 sensor 都不開窗口：reducer 要求每筆事件（任何來源、不論是否被 matcher 引用）都不早於最新保留的 sequence partial 或 window entry（reducer 的 "Sequence source time moved backward" 與 "Window source time moved backward" 檢查）。開窗口的 sample 會在其他來源較晚觀測、立即提交的事件之後才提交，reducer 因此會拋出例外，collector 以 `STORAGE_WRITE_FAILED` 失效並遺失該批，研究接著安全暫停；reducer 的這項檢查不變。
- 機制：consumer 取得批次的第一個 callback 後持續收集，直到自該 callback 擷取起經過 5 秒 consumer dispatcher 的 monotonic 時間（Android 上是清醒時間）。barrier 或 stop 到達時立即提交已收集的批次，再照舊處理，所以暫停、完成、撤回、資源 barrier 與截止時的停止都不等待窗口。token 不相等、observed time 倒退、boot 變更與每批 512 筆上限仍立即結束批次。窗口是 consumer dispatcher 上的 `select` 與 `onTimeout`，沒有新增 WorkManager 工作、alarm、`Handler` 或 wake lock。CPU 在窗口內 suspend 時（沒有 wake lock 的加速度計可能發生），窗口一併暫停，批次在下次喚醒並經過剩餘時間後提交；gyro 持有 wake lock，窗口按實際時間經過。
- 被 admission gate 拒絕的窗口批次，連同已排隊的同 token callback，保留到下一個 barrier、stop 或其他 token 的 callback 前再提交一次；該次仍被拒絕就丟棄。研究截止後、截止 drain 開始前關閉的窗口，因此仍由截止 drain 收入截止前觀測到的 sample。gate 拒絕某個 token 後不再發出相等的 token，所以保留量最多是一批加上佇列容量；超過時 collector 以 `CALLBACK_QUEUE_FULL` 失效。窗口期間 consumer 持續把 callback 從佇列移入批次，記憶體上限為一個最多 512 筆的開啟批次加上 2,048 則的佇列。

對研究資料的影響：

- 原始 sample、observed time（仍是擷取時間）、`source_elapsed_realtime_nanos`、時間解析度與裝置資格不變。
- 交付延遲：這兩個 sensor 的 sample 最多延後 5 秒清醒時間才提交；加速度計在 CPU suspend 時延到下次喚醒之後。
- 持久性：批次被接受前，sample 只存在於程序記憶體；被接受仍代表已同步寫入 commit 或 pending slot。程序死亡時遺失的是該來源最後一筆紀錄之後擷取的 sample，最多約一個窗口。只要持久狀態是 `ACTIVATING`、`RUNNING` 或 `PAUSING`，下次初始化都會提交 `RECOVERY` commit，其中有 `SOURCE_QUALITY_GAP`（`PROCESS_RECOVERY`）並關閉這些 sample 所屬的 epoch，所以遺失不會無聲發生。這個事件只帶自己的時間；對開窗口的來源，缺口應從該來源最後一筆紀錄算起。鏈上前一個 commit 由其他來源、timer 或命令產生時，gyro 最後一筆紀錄最多比它早 5 秒；加速度計的窗口在 CPU suspend 時暫停，所以最多早 5 秒清醒時間再加上其間所有 suspend，可能是數小時。分析不可用 5 秒界定這段缺口。
- 不經品質缺口記錄的遺失：以下三種情況都會先關閉 admission 再暫停 collector，開啟中的窗口因此被拒絕而丟棄，且沒有指名該 sensor 的 `SOURCE_QUALITY_GAP`。(1) 安全暫停（包含儲存失敗與必要資源失效）：最多是 `STUDY_SAFETY_PAUSE_REQUESTED` 之前 5 秒的 sample。(2) 研究進行中的 TIME_SET 或 TIMEZONE_CHANGE（discard barrier）：最多是時間變更前 5 秒的 sample，研究仍維持 `RUNNING` 並換到新 epoch，唯一紀錄是 `source_id` 為 `timer.v1`、原因為 `WALL_CLOCK_CHANGED` 的缺口；分析應把各開窗口來源從該缺口前最後一筆紀錄到新 epoch 啟用視為未觀測。(3) 截止後才首次觀察到的時間變更：研究只以持久輸入完成、不做 drain，開啟窗口中截止前擷取的 sample 被丟棄，而一般的截止 drain 會收入這些 sample。開窗口前，這些情況只會遺失仍在佇列或提交中的 callback。discard barrier 不 drain live 來源，因為 drain 會在 discard commit 中加入 pre-drain observation。
- epoch 歸屬不變：token 在 callback 時擷取；drain 前擷取的 sample 以 pre-drain 輸入收入舊 epoch；force-close 後不收任何 sample。
- 介入暴露：時間型條件（例如 17:00 的 `study_local_window` 邊界）仍以 durable timer 為準。WorkManager 喚醒延遲時，邊界之後的第一個 commit 會讓 reducer 看到轉換；開窗口來源的 commit 比逐筆提交最多晚一個窗口，舊條件（例如限速）因此最多延長 5 秒，直到該 commit、其他來源的 commit 或延遲的 timer，以先到者為準。

量測是原始碼路徑的工作量，不是耗電量測。以下數字與上一節相同，是 2026-09-25 的一次性快照：以 commit `4010b55` 加上本分支尚未提交的工作目錄為基底，設定如各項所列。JVM 量測程式與模擬器上的檔案系統及 frame 寫入計數注入（`EncryptedExperimentStore` 的 internal 建構子可注入這兩者）沒有納入 repository，無法從 repository 重跑；之後的 collector 或 storage 變更可能使這些數字過時，重新評估這項取捨時須重新量測。

- JVM（虛擬時間 60 秒，真實 `SerializedCallbackCollector` 與 `ExperimentRuntime`，記憶體內 StudyStore）：1 Hz 從 60 次 commit 降為 10 次（每批 6 筆，換算每小時 3,600 → 600）；50 Hz 從 3,000 次降為 12 次（每批最多 251 筆，每小時 180,000 → 720）。automation 引用該來源時與修改前完全相同。
- API 34 模擬器（真實 gyroscope collector、`ExperimentRuntime` 與 `EncryptedExperimentStore`，60 秒，含結束時的 flush；store 的可注入檔案系統與 frame 寫入函式計數）：

| 要求的抽樣 | 條件 | sample | commit frame | fsync | Keystore 加密 | 每 1,000 sample 寫入 bytes |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| 1 Hz（模擬器實際約 2 Hz） | 修改前 | 121 | 121 | 126 | 122 | 2,905,289 |
| 1 Hz | 修改後，automation 引用 | 120 | 120 | 125 | 121 | 2,905,541 |
| 1 Hz | 修改後，5 秒窗口 | 121 | 12 | 12 | 12 | 692,016 |
| 50 Hz | 修改前 | 3,001 | 3,000 | 3,235 | 3,047 | 2,960,435 |
| 50 Hz | 修改後，automation 引用 | 3,001 | 3,001 | 3,236 | 3,048 | 2,961,241 |
| 50 Hz | 修改後，5 秒窗口 | 3,000 | 12 | 17 | 13 | 466,001 |

fsync 包含每個 frame 一次、快照與 pending slot 的檔案及目錄同步；Keystore 加密包含 frame、快照與 pending 文件。修改前每 64 次 commit 寫一次快照（50 Hz 時 47 次，每次 2 個檔案同步與 3 個目錄同步）；開窗口後 frame 變大，快照改由 1 MiB 條件觸發（50 Hz 時 60 秒 1 次）。由 50 Hz 的兩組 frame bytes 推得，每個 sample 約佔 455 bytes，每個 commit 另有約 2.4 KB 與 sample 數無關的內容；開窗口後後者分攤到約 250 筆 sample，所以每 1,000 sample 的寫入量下降。以每小時換算：50 Hz 時 commit 約 180,000 → 720，fsync 約 194,100 → 1,020，Keystore 加密約 182,820 → 780。

五天範本的 gyro 以 1 Hz 在每天 12:00–17:00 收集，名目上共 25 小時、90,000 筆 sample；逐筆提交約 90,000 次 commit，開窗口後約 15,000 次（依上方 JVM 量得的每批 6 筆，即每小時 600 次）。這是名目推算，不是實測；實際 sample 頻率由平台決定。gyro 的 partial wake lock 仍在，CPU 仍會在收集期間保持喚醒，這部分仍需第 3 節。

驗證：`SerializedCallbackCollectorTest` 涵蓋窗口在第一次擷取後 5 秒關閉、窗口以擷取時間而非 consumer 取得時間計算、barrier／stop 在窗口內立即提交且不等待、大小上限與 token 不相等及時間倒退／boot 變更仍立即結束批次、被 automation 引用時不開窗口、取消時開啟批次不會延後提交、被拒絕批次保留到下一個 barrier、最終拒絕時丟棄且 ordinal 連續、佇列滿時仍以 `CALLBACK_QUEUE_FULL` 失效、保留量上限與窗口長度上限；`ExperimentRuntimeTest` 以真實 runtime 驗證跨資源 barrier 的 epoch 歸屬與不開窗口時相同（開窗口時 sample 由 barrier 以 pre-drain 收入）、force-close 後不收、研究進行中的時間變更拒絕開啟的窗口且只記錄 `timer.v1` 缺口、截止後才觀察到的時間變更拒絕開啟的窗口、截止完成時先收入開啟的窗口、截止後才關閉的窗口由截止 drain 收入、timer 未送達時時間型轉換要等開窗口的批次提交才被 reducer 看到，以及其他來源的 window 狀態下組裝規則讓 gyro 逐筆提交而保持 `ACTIVE`（只依 matcher 開窗口時 collector 會以 `STORAGE_WRITE_FAILED` 失效）；`SensorAutomationReferenceTest` 以產生的 registry 驗證哪些 automation 會引用 sensor，以及哪些 program 保有依事件時間排序的 sequence／window 狀態；`CollectorAutomationReferenceAssemblyTest` 與 `CollectorResourceActuatorTest` 驗證組裝時的 `requiresPromptCommits` 旗標，包括有 window 狀態時每個來源都要求逐筆提交；`P2CollectorEmulatorTest` 在模擬器上驗證只有 gyro 的 observation 跨越時間。

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

**狀態（2026-09-25）：**已實作「collector 多筆提交」中只合併已排隊 callback 的部分，見上方「第 2 項實作狀態」；gyro 與加速度計在沒有 automation 引用、且研究沒有 sequence／window 狀態時另以最多 5 秒的窗口批次提交，見「第 2 項後續」，其同步次數、加密次數與寫入 bytes 已在模擬器上以加密儲存量測。硬體批次回報與「多個邏輯 commit 合併一次同步」尚未實作；commit 延遲與實機能耗尚未量測。

## 3. 陀螺儀長時間持有 partial wake lock

`GyroscopeCollector` 固定 `keepCpuAwake = true`；`AndroidSensorCollector` 在註冊時以 `wakeLock?.acquire()` 取得 wake lock，直到暫停、停止或回滾才釋放。五天範本的 `maximum_report_latency_us` 為零。

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

修改前基線的 `ExperimentRuntime` 在等候 mutex 時以 1 ms 重試輪詢，在儲存壅塞下可能放大排程成本。**已完成（2026-09-07）：**見本文開頭「本次實作結果」；現在的 `AdmissionLock.kt` `withLockUntilDrain` 先 `tryLock`，否則以 `select` 等待 drain 訊號或鎖取得，沒有輪詢或計時器，取消與鎖交接會清理自身所有權，並保留 gate 與 barrier 的競態保證。

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
| D | 共用非緊急工作節奏、調整顯示更新（1 ms lock 重試已於 2026-09-07 移除） | 保留控制邊界與撤銷處理時限 |
| E | 依 trace 優化 VPN packet path；評估 30／60 秒研究輪詢設定 | 輪詢時間解析度及 VPN 暴露條件須明訂 |

先使用相同機型、網路、亮度、電量／溫度區間與可重現負載，分別量測：裝置閒置、現行完整研究、只做 A、A+B、經驗證的 C，以及 baseline／limited VPN 的 idle 和固定流量。各條件重複且交錯順序，避免溫度與電池狀態偏差。測試應涵蓋熄屏與亮屏，並與網路限速處置分層比較。

使用 Perfetto／System Trace 與支援機型的 Power Profiler，收集整機能量／平均功率、CPU 時間、suspend residency、wake lock duration、磁碟 I/O、同步次數、GC、queue backlog、sample coverage、控制邊界延遲。ODPM 是整機功率，不能直接標成此 App 的獨占耗電。[Android Power Profiler](https://developer.android.com/studio/profile/power-profiler)

短期 trace 找到成本後，再進行熄屏長測及完整 120 小時實機試跑。測試功耗時應處理 USB 充電、ADB 與持續 profiler 本身的干擾，並對照相同負載；不能只用電池百分比或 debug 模擬器得出節電比例。本報告不承諾任何未量測的省電百分比。
