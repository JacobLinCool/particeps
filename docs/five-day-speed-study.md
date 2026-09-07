# 五天限速研究設定與資料解讀

本研究以每位參與者按下開始的當地日期為第 1 天，第 3、4、5 天的當地時間 12:00–17:00 限速，上傳與下載各 500 kbps（0.5 Mbps）。每個限速日的 17:00 產生一份 App 內活動問卷邀請。網路速度使用實際流量計數，不進行主動測速。

完整範本是 [`five-day-speed-study.json`](../researcher-tools/examples/five-day-speed-study.json)。範本已依本研究需求設定 `target_packages: "all"`，使用公開測試金鑰；正式研究前必須填入真正的研究資料、公鑰與同意內容。

## 功能核對

| 需求 | 實作與設定 | 解讀界線 |
| --- | --- | --- |
| 前兩天不限速，後三天每日 12:00–17:00 限速 | 新增 `study_local_window` 條件；`first_day: 3`、`last_day: 5`、`start_local_time: "12:00"`、`end_local_time: "17:00"` | 左閉右開區間；其餘時間套用 `baseline`，上／下載上限皆為 `null`，不施加限速；研究 VPN 在整個進行中的 session 保持作用 |
| 限速結束後問卷 | 既有原生 survey 與通知 outbox，分別以第 3、4、5 天的 17:00 條件觸發，每個規則 `maximum_activations: 1` | 保證持久化觸發與重試識別，不保證通知恰於整點顯示或參與者已看見 |
| 螢幕開啟，包含未解鎖 | 新增 `screen_state.v1`，記錄預設顯示器的 `display_state`、`interactive`、`keyguard_locked` | 面板電源、可互動與解鎖是不同變數；DOZE／常亮顯示須獨立處理，不能歸為正常亮屏使用 |
| 陀螺儀 | 既有 `gyroscope.v1`；啟用期間持有 CPU partial wake lock，暫停／停止／註冊回滾時釋放 | 不點亮螢幕；有耗電成本，硬體回報速率與系統限制仍須實機確認 |
| 通知接收 | 新增 `notification_events.v1` 與 Android 通知存取設定 | 記錄張貼 App、系統 post time、接收 callback time、研究範圍 HMAC token。不讀文字／標題；同 token 可為更新，不代表新訊息、閱讀或伺服器送達 |
| 實際傳輸速度 | 新增 `network_throughput.v1`，以 `TrafficStats` 記錄裝置總收發 bytes 差與實際單調時間區間 | 不主動耗流量；包含 OS 的介面計數語義，VPN 介面可能有重疊計數；不是特定 App 或實體網路的容量測速 |
| 網路連線，包含 VPN | 既有 `network_state.v1` 的預設路由事件，加上獨立 VPN callback，包含其他 UID 的 VPN | 預設路由上的 `vpn` 與 `VPN_STATUS` 分開分析。監聽剛啟動、尚未收到 VPN 證據時 `connected` 省略，表示未知；不以假 `false` 代替 |
| App 開啟／離開時間與使用時間 | 既有 `usage_events.v1` 的 `ACTIVITY_RESUMED`、`ACTIVITY_PAUSED`、`ACTIVITY_STOPPED`，含來源時間、package 與活動元件 token | 是 Activity 前景／離開事件，不是程序啟動／被殺時間；Android 可延遲或缺漏回報，不能把它稱為完整精確的 App session |

「螢幕可互動」與面板亮起的差異見 [Android PowerManager](https://developer.android.com/reference/android/os/PowerManager#isInteractive())。通知 callback 的平台契約見 [NotificationListenerService](https://developer.android.com/reference/android/service/notification/NotificationListenerService)。VPN 觀測的涵蓋範圍見 [NetworkRequest.Builder](https://developer.android.com/reference/android/net/NetworkRequest.Builder#setIncludeOtherUidNetworks(boolean))。

## 五天與時區的定義

範本的 `duration_hours: 120` 表示開始後完整 120 小時，暫停不延長截止時間。研究日按當地日期計算，因此週一 10:00 開始，限速於週三、四、五 12:00–17:00，收集截止為週六 10:00。這與「第 5 個當地日期午夜結束」不同。

`study_local_window` 使用每次輸入所記錄的裝置時區，把研究開始 UTC 時刻與目前 UTC 時刻轉為當地日期。跨日以日曆日期計算，並非每次加上 24 小時。裝置更改時區會重新解讀兩個日期與時段；研究開始時刻本身不改變。`temporal_context.v1` 提供分析需要的時區變化記錄。日光節約時間造成不存在的開始／結束邊界時跳過該日；重複時間選較早的 UTC 時刻，與既有每日排程一致。

手機必須持續在研究進行中才會收集與執行限速。關機、重啟、強制停止、權限撤銷、儲存失敗、缺少必要硬體或 VPN 衝突，皆不能承諾無缺口。必要來源失效會暫停研究；程序死亡／重啟後要求參與者明確繼續，並保留品質缺口，不回填未驗證的區間。

Android 喚醒、資源切換、背景工作與通知有實際延遲。`study_local_window` 在每個已提交輸入與持久化邊界計時器上重新計算；分析應以條件 epoch、資源套用／釋放 receipts 與問卷事件確認實際時間，不把設定的 12:00–17:00 當作已證明的實際處置時間。

## 範本的主要數值

| 設定 | 範本值 |
| --- | --- |
| `duration_hours` | `120` |
| `gyroscope.v1` | `sampling_period_us: 1000000`（請求 1 Hz）、`maximum_report_latency_us: 0` |
| `network_throughput.v1` | `poll_interval_seconds: 10` |
| `usage_events.v1` | `poll_interval_seconds: 15` |
| `network_state.v1` | `include_bandwidth_estimates: true`，估值與實際流量分開保存 |
| 七個收集器 | 全部 `required: true`，整個進行中的研究 session 綁定 `continuous` |
| 上／下載上限 | 各 `500` kbps，對目前 Android 使用者空間所有 App 的合計流量限速，並非每個 App 各有 500 kbps |
| 問卷 | 每日規則最多一次、可填寫 6 小時；同日恢復研究不再建立第二份 |
| `storage.maximum_local_bytes` | `8589934592`（8 GiB 上限，不是實際空間需求保證） |
| `upload` | `{}`，範本由參與者手動匯出 |

限速沿用原生引擎的 aggregate token bucket，每個方向有 2 秒的容量；短區間可能因突發額度而高於 500 kbps。評估實際處置時應同時檢查限速 receipts、原生流量稽核與持續區間速率。

1 Hz 是可調整的範本設定，不代表足以辨識活動。若研究需要更高頻率，必須先用實際研究裝置測量儲存成長與電量，調整抽樣和上傳計畫；每筆事件另有 provenance 與 encrypted commit 開銷，不能只以三個浮點數估算空間。配額不足會停止收集。

問卷詢問今天 12:00–17:00 的主要活動，提供多選與文字補充。每個日規則使用 `[17:00, 23:59)` 的邀請條件；若該時段全程暫停，該日問卷不在隔日補發。若晚些時候才恢復，邀請可在該時段內建立，並記錄實際觸發時間。這不是已收到三份答卷的保證。

## 簽署與匯入

`traffic_shaping.target_packages: "all"` 明確選擇所有 App，包含研究 App 與研究期間新安裝的 App；不是列舉當下已安裝 App 的快照。空陣列與其他字串皆不合法。修改研究者資訊、有效期限、同意內容和問卷。加入後不需個別修改研究開始日期。`baseline` 讓研究 VPN 在不限速與限速期間皆存在，因此比較主要改變限速值，避免只在處置時段才加入 VPN 路徑。此範本的限速為全部 App 共用上／下載各 500 kbps，非每個 App 各自 500 kbps。VPN 範圍限於目前 Android 使用者空間，不延伸至其他使用者、工作設定檔或熱點分享裝置。VPN 引擎本身的轉送 socket 使用 `protect`，避免封包重新進入 VPN 迴圈；不替一般 App 設置 bypass。參見 [Android VPN 路由契約](https://developer.android.com/reference/android/net/VpnService.Builder#addAllowedApplication(java.lang.String))。

使用自己的 Ed25519 簽章與 X25519 匯出加密金鑰；將公鑰填入 `signer` 與 `export`，私鑰只交給研究者 CLI。從儲存庫根目錄執行：

```bash
./gradlew :researcher-tools:run --args='canonicalize --input researcher-tools/examples/five-day-speed-study.json --output /tmp/five-day-study.canonical.json'
./gradlew :researcher-tools:run --args='sign --config /tmp/five-day-study.canonical.json --private /absolute/path/study-signing-private.key --key-id YOUR_SIGNER_KEY_ID --output /tmp/five-day-study.partcfg'
./gradlew :researcher-tools:run --args='check-config --envelope /tmp/five-day-study.partcfg --app-version 1'
```

CLI 拒絕覆寫既有輸出；重跑時選擇新的輸出路徑。參與者匯入新版 App 的 `.partcfg` 後，在既有設定流程授予通知顯示、通知存取與 Usage Access，接受 VPN consent，確認陀螺儀可用，再按開始。通知顯示權限與讀取通知事件的特殊存取是兩個不同權限。其他 VPN 與研究 VPN 無法在同一使用者空間同時作為作用中的 VPN。

## 分析方式

實際傳輸量以 `network_throughput.v1 / NETWORK_THROUGHPUT` 為準：

```text
interval_seconds = (interval_end_elapsed_nanos - interval_start_elapsed_nanos) / 1e9
download_kbps = rx_bytes * 8 / interval_seconds / 1000
upload_kbps   = tx_bytes * 8 / interval_seconds / 1000
```

零 bytes 表示該計數器在該區間沒有流量，不表示網路容量為零。計數器不支援、倒退或時間不遞增會使來源失敗，不能輸出假的零值。每次 admission 重新建立基準，區間不跨越暫停；暫停前不足一個採樣區間的尾段不會被偽裝成完整區間。

App 使用時間從 `source_time_utc_millis` 計算，依參與者、boot／condition epoch、package 和 `activity_component_token` 配對 resumed 與首次 paused／stopped。再對同 package 的重疊前景區間取聯集，避免多 Activity 或多視窗重複計時。缺少起點／終點、重啟、暫停和來源缺口皆標成截尾／未知，不能以輪詢時間或下一個無關事件補成精確關閉時間。原始資料包含事件與分析所需時間，沒有聲稱 Android 已提供完整 App session。

正式招募前至少在目標 OEM 裝置完成完整五天試跑，包含鎖屏感測、通知更新、VPN 切換、沒有流量時的計數器、暫停／恢復、斷網與截止時間；模擬器與單元測試不能替代五天的實機完整度評估。

## 開發驗證

Android 主機整合測試使用本機 TCP fixture，不是正式研究 App 的測速功能。先在主機執行 `python3 tools/all_apps_fixture_server.py`，建置並安裝 debug 與 androidTest APK，再於指定模擬器執行：

```bash
adb -s emulator-5580 shell am instrument -w -r -e class cool.jacoblin.particeps.AllAppsTrafficShapingAndroidTest,cool.jacoblin.particeps.StudySourcesAndroidTest -e traffic_test_endpoint 10.0.2.2:18766 cool.jacoblin.particeps.test/androidx.test.runner.AndroidJUnitRunner
```

VPN 測試確認研究 App 的預設路由進入全 App VPN、收到完整測試內容、下載受 500 kbps 限制，以及解除資源後清理。未提供本機 endpoint 時該測試明確跳過；不能把跳過列為通過。資料來源測試驗證通知 metadata、亮／熄屏、熄屏陀螺儀、連線觀測、被動 bytes 計數與暫停後不再送出事件。

2026-09-06 驗證：Android 單元測試、lint 與 APK 建置通過；API 34 模擬器的上述兩項整合測試通過（非跳過）；Web 227 項測試通過，Python 67 項測試中 1 項既有測試跳過、其餘通過。範本已完成 canonicalize、公開 fixture 簽署與 check-config 驗證。尚未完成 120 小時實機試跑。
