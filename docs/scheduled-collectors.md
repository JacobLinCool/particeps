# 依資料來源設定每日收集時段

每個 collector 有自己的 `resource_binding`，可獨立選擇每日收集時段、在時段外停用，或切換另一個抽樣／查詢設定。`required` 與收集時段分開：必要來源可以排程停用，但排程要求收集時，啟用或驗證失敗仍會安全暫停研究。

原本驗證器把必要來源與全程啟用綁在一起；本次已調整 Kotlin、Web 與 Python 分析端的規則，並保留資料重播驗證。分析端不能把「本來應收集卻停了」當成合法排程停用。

## 可直接使用的研究範例

[`five-day-windowed-gyro-study.json`](../researcher-tools/examples/five-day-windowed-gyro-study.json) 是原五天研究的獨立變體：

| 工作 | 收集／作用時段 |
| --- | --- |
| 陀螺儀，請求 1 Hz | 每個研究涵蓋的當地日期 12:00–17:00，時段外停止 |
| 螢幕狀態、通知、網路狀態、VPN 狀態、被動網速、App 使用、時間情境 | 全部 120 小時 |
| 全 App VPN | 全部 120 小時；第 3–5 個當地日期 12:00–17:00 上／下載各 500 kbps |
| 活動問卷 | 第 3–5 個當地日期的限速時段結束後 |

這份範例保留必要來源的失敗處理與其他持續 collector。它使用公開測試金鑰，只能用於開發驗證；正式研究須替換金鑰並重新簽署。原本全程收集的範例保留供對照。

在 `collectors` 中保留陀螺儀及具名 profile：

```json
{
  "id": "gyroscope.v1",
  "required": true,
  "profiles": [{
    "id": "continuous",
    "config": {"sampling_period_us": 1000000, "maximum_report_latency_us": 0}
  }]
}
```

將其唯一的 resource binding 設為：

```json
{
  "type": "resource_binding",
  "id": "bind-gyroscope",
  "resource": {"kind": "collector", "id": "gyroscope.v1"},
  "cases": [{
    "condition": {
      "type": "study_local_window",
      "first_day": 1,
      "last_day": 366,
      "start_local_time": "12:00",
      "end_local_time": "17:00"
    },
    "profile_id": "continuous"
  }],
  "default_profile_id": null
}
```

`first_day: 1`、`last_day: 366` 讓每日規則涵蓋整個研究；**研究仍在 120 小時截止**，不會變成 366 天。這也涵蓋完整 120 小時結束當天的部分時段。若只關注第 3–5 個研究日，改成 3 與 5。`continuous` 只是 profile 名稱；何時收集由 binding 決定。

時間區間含開始、不含結束，即 `[12:00, 17:00)`。使用裝置目前所記錄的時區與研究開始時刻換算當地日期。跨日、時區與日光節約時間語義沿用 [五天研究說明](five-day-speed-study.md)。同一 collector 可有多個時段條件，按清單順序套用第一個成立的條件，最多 16 個 cases；不同 collector 不必使用相同的條件或 profile。

## 時段外改為低頻

新增低頻 profile，再把 `default_profile_id` 設為該 profile 的 ID。例如 App 使用查詢在關注時段使用 15 秒 profile，時段外使用 300 秒 profile；原始事件時間與查詢交付時間不同，較慢查詢增加交付延遲。

對陀螺儀，可設定白天 `sampling_period_us: 100000`（請求 10 Hz），時段外 `1000000`（請求 1 Hz）。目前允許的最大請求週期為 1 秒；原範例已是 1 Hz，因此無法只靠既有 profile 再設定為每 10 秒取一次。抽樣週期是 Android 提示，不能保證實際回報率。

**陀螺儀停用與降頻的省電效果不同。** 現有陀螺儀任何非空 profile 都持續持有 partial wake lock，降頻主要降低事件處理與寫入量。切為 `null` 時會經過 barrier、停止來源、註銷感測器並釋放 wake lock，讓系統有機會休眠。下一個時段會用新的資源 generation 重新啟用；分析應依實際 epoch／receipt 判斷收集邊界。

固定時區、無 DST／暫停／延遲時，完整 120 小時內每天五小時的交集總長為 25 小時，陀螺儀持續啟用時間減少約 79.2%。其他來源、VPN 與使用者本身仍會耗電；不能把這個比例當成整個 App 的節電率。

## Web 編輯方式

1. 在資料來源中保留「必要」，建立所需 profiles。
2. 在該來源的資源規則選「研究日與當地時段」，設定日期範圍與 12:00–17:00。
3. 時段內選擇收集 profile；預設選「停用」，或選另一個低頻 profile。
4. 分別設定其他來源的規則，再驗證、簽署新的研究設定。

切換「必要」不會改寫已設定的時段。已加入的研究不會自動套用新檔；需使用新簽署組態開始研究。

## 保留的限制與實機驗證

- 被其他 automation 當成事件觸發／條件來源的 collector，仍必須必要且全程啟用，避免停止自身的再啟動依據。純時間條件由 runtime 的持久化 timer 驅動，不依賴陀螺儀事件。
- 「必要」來源的硬體／權限仍是整份研究的前置條件；在停用時段撤銷必要權限，也會依既有政策暫停研究。排程停用不是撤銷研究授權。
- 必要 actuator 仍須保持啟用；此修改允許各資料 collector 自訂收集時段，沒有改變全 App VPN 的基線設計。
- Android 邊界喚醒目前採 WorkManager；其 delay 是最早可執行時間，不保證精準 12:00 喚醒。Doze 可延後工作。若 12:20 才執行，只能從實際執行時間收集；若已過 17:00，不會補造當日資料。[WorkManager](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)、[Doze](https://developer.android.com/training/monitoring-device-state/doze-standby)
- 若全部 collector 都停用，最後一個來源釋放後會停止 collection 前景服務；下一次純背景啟動可能受到 Android 前景服務限制。本範例保留其他持續來源，但這不會讓 WorkManager 變成精確排程器。
- 程序／裝置重啟仍採既有安全暫停與使用者恢復流程，不保證自動重新收集。任何背景延遲都必須透過實際收集證據評估，不能只用 JSON 時間視為成功。

本次驗證涵蓋設定往返、跨日 timer 開關、不同 collector 互不影響，以及必要來源到點啟用失敗仍安全暫停。Python 分析端也驗證 observation 的來源確實在該 epoch 啟用且 generation 相符，拒絕在排程關閉來源上偽造資料。相關 Kotlin／Web／Python 測試通過，Web 型別檢查與建置、Android lint 與 APK 建置成功；完整範例已用測試金鑰完成 canonicalize／sign／check-config。

這些檢查不包含 Pixel 10a 的 Doze 邊界延遲或 120 小時實機測試。若研究要求在休眠中接近準點啟動，下一項平台工作是具備授權、撤銷處理與實機驗證的精確 alarm 控制邊界，不能以目前 WorkManager 測試宣稱已完成。
