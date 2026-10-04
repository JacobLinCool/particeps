# 五天研究的 App 前景使用分析

先以同版本 `particeps-analysis materialize` 驗證、解密並產生 Parquet，再執行：

```sh
particeps-analysis usage-report \
  --dataset /secure/datasets/study \
  --output /secure/reports/usage \
  --timezone Asia/Taipei \
  --duration-hours 120
```

輸出目錄必須尚不存在。工具驗證 dataset manifest、每份 Parquet 的 SHA-256、列數與分區身分，以及 `quality-summary.json` 的 digest。缺少新版 coverage 欄位或品質摘要 digest 時，須重新 materialize；不推測舊資料的查詢涵蓋範圍。來源資料不會被改寫。

`windows.csv` 每位參與者、每個涵蓋的當地日期分成 `before`（00:00–12:00）、`during`（12:00–17:00）、`after`（17:00–24:00）。每個時段有各 App 與 `all_apps` 列。時段與 `STUDY_STARTED` 後指定的小時數取交集，第一天、最後一天可能不是完整日。`--timezone` 是固定分析參照時區，不代表手機在實驗當時的動態時區或實際處置排程。

主要欄位：

| 欄位 | 意義 |
| --- | --- |
| `paired_foreground_millis` | 由可配對 Activity resumed 與首次 paused/stopped 重建的前景區間，按時段裁切後取聯集；同 App 多 Activity、多視窗不重複累加。`all_apps` 另對所有 App 取聯集，因此通常小於各 App 相加。 |
| `observed_resumed_count` | 此來源時間時段內的 resumed 事件數，不等同完整 App 啟動次數。 |
| `covered_resumed_count` | 其中落在經驗證查詢 coverage 且來源已啟用區間內的 resumed 次數。 |
| `censored_resumes` | 有開始而未找到可接受結束的事件數，歸在開始時段。包含新 resumed 覆蓋尚未結束的舊 resumed。 |
| `unmatched_closes` | 無可接受開始的 paused/stopped 數，歸在結束時段。同 Activity 的 paused 後緊接的 stopped 不再重複計算；之後另一次孤立 paused 仍計入。 |
| `outside_coverage_events` | 不在經驗證來源啟用 coverage 內的事件，不用於配對時長。 |
| `query_covered_millis` | 成功提交的來源查詢 coverage，與來源 epoch 的 activated/deactivated 時間、研究分析範圍及最新提交時間取交集，再取聯集。排除 preparation 階段。 |
| `planned_millis` / `reached_millis` | 預定時段長度，以及截至最新已提交時間已經到達的部分。未來時段不列為已發生的缺口。 |
| `query_coverage_of_reached` / `query_coverage_of_planned` | 查詢 coverage 對上述兩種分母的比例。尚未到達的時段，前者留空。 |

配對以 package 與 `activity_component_token` 為鍵，使用 `source_time_utc_millis` 排序，時間相同再依事件 sequence。只在同一 boot、連續的來源 coverage 內配對；來源／runtime 品質缺口與時鐘不連續會切斷配對。相接且已啟用的 epoch 可連接，避免僅因限速切換就丟掉跨 12:00／17:00 的前景區間。缺少結束不補到下一個不相關事件、收集截止或匯出時間。

**這是依已交付事件配對的前景估計，可能低估或高估真實使用時間。** 若 Android 漏掉區間中間的 paused 與下一次 resumed，首尾雖然可配對，仍可能把中間未使用的時間算入；缺少端點則可能低估。Activity token 也不是獨立 Activity instance 的識別碼。有 coverage 的 `0` 只表示沒有可配對區間，不表示沒使用手機；無 coverage 的時長留空，`usage_status` 標示 `no_query_coverage`，有 coverage 時標示 `observed_paired_intervals`。Coverage 表示 App 成功查詢該區間，不保證 Android 完整交付所有事件，也不保證螢幕前有人操作。未閉合 session 的部分時長完全不估補，判讀時必須同看截尾數。

`participants.jsonl` 提供參與者的研究起點、預定結束、最新提交時間、完整分析範圍／已到達範圍 coverage，以及截尾數。`partial` 表示尚未到達預定截止；`horizon_reached` 也不表示連續收集成功。若存在時鐘不連續，固定 UTC 分段與截止的解讀會受影響，另標 `calendar_horizon_interpretation: wall_clock_affected`，應檢視原始時間證據。報告從不宣称 `complete_collection_proven`。

`traffic-conditions.csv` 每位參與者、每日、時段另列 runtime 記錄的處置時間，**不是實際網速達到 500 kbps 的證明**，也不把 `during` 當成必然有限速：

| 欄位 | 意義 |
| --- | --- |
| `runtime_limited_millis` | 至少一個方向有 cap 的已套用 profile 區間；任意有限 cap 都計入。 |
| `runtime_500_500_millis` | 其中上、下載 cap 都正好為 500 kbps 的部分，是上一欄的子集合，不另加總。 |
| `runtime_unlimited_millis` | 有 APPLIED receipt 且兩個方向 cap 都省略的區間；缺 receipt 不推定為不限速。 |
| `unknown_profile_millis` | 已到達時段內，無法以有效 receipt 區間確認 profile 的部分。 |
| `not_yet_reached_millis` | 預定時段尚未到達的部分，不算處置缺口。 |

工具讀取 `TRAFFIC_SHAPING_PROFILE_APPLIED` 的 `payload_condition_epoch_id`、兩方向 cap、resource generation、VPN generation 與 activation research time，對照已驗證 epoch。每段至少還要有同 epoch、同 boot、同 profile／generation 的 `TRAFFIC_SHAPING_SNAPSHOT`。有對應 REMOVED 與邊界 snapshot 的已結束 epoch，計算到 removal boundary；否則只計算到最後一份 traffic snapshot 的實際 observation time。單獨的 APPLIED 只有起點證據，不推估任何時長。

程序恢復後，epoch 的 deactivated time 可能是發現問題的時間。因此 `PROCESS_RECOVERY`、缺少 REMOVED 或尚未關閉的 epoch，最後 traffic snapshot 到恢復／最新提交時間之間都列 unknown，不把恢復當下當作持續限速的證明。每個 snapshot 與 removal 還必須相對 activation 保持壁鐘與單調時間增量一致（允許 1 毫秒整數時間精度差）；遇到第一次不一致，該 epoch 只算到此前最後一致的 snapshot。較大的時間讀取偏差也會保守留下 unknown，後續時鐘調回不會補回該段。所有區間另裁到研究分析範圍、最新提交時間與保持同一時鐘的 epoch 邊界；壁鐘變動造成不同 epoch 區間重疊時，重疊部分也列 unknown。每時段 `limited + unlimited + unknown = reached`。

`report.json` 保存輸入摘要 digest、分析時區與限制。請結合原始流量稽核、實際網路情況及資料完整度解讀處置時間；報告不推論跨裝置替代或限速因果。

原始使用事件與重建區間在私人 SQLite 暫存檔排序，Parquet 以固定大小 batch 讀取，完成後移除暫存並以不可覆寫的原子改名發佈整份報告。報告含參與者與 App 名稱，應與解密後研究資料採相同的存取保護。
