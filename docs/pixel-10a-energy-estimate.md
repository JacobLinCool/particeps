# Pixel 10a：120 小時研究的條件式耗能試算

更新：2026-09-09（移除跨 App 通知事件工作量）。使用目前完成儲存／runtime 最佳化後的程式與 `researcher-tools/examples/five-day-speed-study.json`。完整研究的示例情境為 **93 mW、120 小時 11.19 Wh，折合每天約 11.4 個電量百分點**。

**這是未校準的參數情境。** Google 公布 Pixel 10a 使用 Tensor G4、典型電池容量 5,100 mAh；本次未取得 Pixel 10a 的完整功耗係數或該 App 的實機 trace。下面的功率與單次能量係數全部是明示的試算輸入，並非該機的測量值；低／中／高情境不是實際上下界、信賴區間或典型使用者預測。沒有以 Android Emulator 的電池讀數推算實機耗電。[Google 官方規格](https://store.google.com/us/product/pixel_10a_specs?hl=en-us)

## 1. 不同功能組合

各格為「每天增加的電量百分點」，分母是全新典型容量。每列包含研究 App 的共用處理、該列功能與加密儲存，不能再把各列相加。

| 啟用功能 | 低係數情境 | 示例情境 | 高係數情境 |
| --- | ---: | ---: | ---: |
| 螢幕狀態＋網路連線／VPN 狀態＋時間情境 | 0.12 | 0.37 | 0.86 |
| 上列＋被動網速讀取＋App 使用紀錄查詢 | 0.25 | 0.63 | 1.44 |
| 上列＋連續陀螺儀 | 5.28 | 11.00 | 22.88 |
| 完整研究：上列＋全 App VPN／限速＋三次問卷 | **5.42** | **11.40** | **24.31** |
| 診斷對照：完整研究移除陀螺儀 | 0.69 | 1.64 | 4.10 |

網路連線／VPN「狀態紀錄」與實際執行全 App VPN 是不同工作。移除陀螺儀的列只用來拆解成本，不滿足原研究需求。沒有持續 wake lock 時，普通輪詢會因 suspend 延遲；本表也降低了這些列的實際查詢次數，不能宣稱所有列具有相同的網速時間解析度。

| 完整研究 | 平均增量功率 | 120 小時增量能量 | 相當於 5,100 mAh 容量 |
| --- | ---: | ---: | ---: |
| 低係數情境 | 44.3 mW | 5.32 Wh | 27.1% |
| 示例情境 | 93.2 mW | 11.19 Wh | 57.0% |
| 高係數情境 | 198.9 mW | 23.87 Wh | 121.5% |

120 小時超過 100% 表示消耗超過一次滿電容量的能量。這些數字是相同日常使用負載下研究 App 所增加的成本，沒有包括使用者原本的螢幕、遊戲、通話等整機耗電；也不是「五天不充電後的電量」或續航承諾。

## 2. 計算方式

固定工作用功率乘時數；離散工作用次數乘每次能量：

```text
E_mWh = T_h × (P_runtime + a × ΔP_awake + x_gyro × P_sensor
               + x_vpn × P_vpn_service)
        + [N_events × e_event + N_throughput × e_throughput_query
           + N_usage_polls × e_usage_query + N_commits × e_commit
           + N_snapshots × e_snapshot + N_audits × e_audit
           + D_vpn_MB × e_vpn_MB + N_packets × e_packet
           + N_connections × e_connection + N_keepalives × e_keepalive] / 3600
        + T_limited_h × (P_limiter + ΔP_radio_treatment)
        + E_survey_ui

P_average_mW = E_mWh / T_h
Battery_energy_mWh = 5100 mAh × 3.85 V = 19635 mWh
Capacity_percentage_points_per_day = 100 × E_mWh / 19635 / (T_h / 24)
```

所有 `P` 單位為 mW，`e` 為 mJ／次或 mJ／MB；`1 mWh = 3600 mJ`。3.85 V 是換算假設，尚未確認為 Pixel 10a 的額定平均電壓。若電壓改為 V、有效容量改為 C，同一能量下的百分比乘以 `(3.85 / V) × (5100 / C)`。

`a` 是「因 App 而額外保持喚醒的時間／全部時間」，只計一次。所有事件、查詢、加密儲存與 VPN 操作係數都是高於已喚醒閒置狀態的增量；感測器項只包括感測器／hub，避免再算一次 CPU 保持喚醒的成本。

本例原本可 suspend 的時間占 80%，連續 gyro 讓 `a = 0.8`；不是把持有 wake lock 的 120 小時全部視為新增 CPU 工作。其他組合的 `a` 分別假設為 0.01、0.03、0.08，均為整組功能的聯集輸入，沒有直接相加各功能的獨立喚醒時間。

Android 本身也以裝置功耗資料與子系統活動時間估算電量；係數仍需來自目標裝置。AOSP 頁面的範例電流不等於 Pixel 10a 的電流。[AOSP 功耗資料契約](https://source.android.com/docs/core/power/values)

## 3. 實際代入的條件

- 連續運作 120 小時，無暫停、無時區切換；第 3–5 個當地日期各限速五小時，共 15 小時。VPN 其餘 105 小時仍轉送，只是不限制速度。
- 陀螺儀**實際收到** 1 Hz、每個 callback 一筆 commit。這不是保證：Android 的 `sampling_period_us = 1000000` 是請求提示。
- 有 gyro 時，網速每 10 秒、UsageEvents 每 15 秒、VPN 稽核每 60 秒的名目工作量完整發生。沒有 gyro 的兩個輪詢情境，實際 poll 次數假設為名目值的 23%／28%，是簡化的 suspend 工作負載，不是 Android 排程公式；並假設較慢的 UsageEvents 查詢仍完整取回當日原始事件。
- 每日螢幕事件 200、網路狀態與 VPN 狀態事件合計 100、時區／時間事件 1、UsageEvents 原始事件 1,000；另留每日 120 筆控制 commit。這些是事件數，不是解鎖次數的實測統計。網路與 VPN 分成兩個 collector 後仍共用合計 100 筆的假設事件預算；未因來源拆分而增加未經測量的功耗係數。
- 原始碼確認 UsageEvents 即使查不到事件也會提交 coverage；每次 VPN 稽核是四筆記錄事件、一筆 commit。時間來源每分鐘檢查不代表每分鐘都產生事件。
- 所有 App 合計每天穿越 TUN 一次計算的上下載資料為 **1,000 MB**，平均封包 1,000 bytes、每小時 20 個新連線；不以可能包含 VPN 重複計數的全機 TrafficStats 直接代替。假設流量跨時段均勻分配總量，因此 15 小時限速期共 625 MB，未超過速率上限允許的量。這不預測參與者限速後的行為或傳輸時長。
- 本例假設無長時間閒置 TCP 連線產生 keepalive 探測。程式確實啟用 TCP keepalive；有探測時須代入次數，並另校準它造成的喚醒與 radio 成本。
- 關閉自動上傳，無匯出／重啟／故障／補傳。本例每份問卷耗時 2 分鐘，額外 UI 功率假設 500 mW，三份共 50 mWh。
- 快照使用已完成的 64 commits／1 MiB 策略。假設每筆 frame 不超過 8 KiB，故本例由筆數先觸發；再預留 100 次強制快照。強制快照會重設筆數，直接加 100 是工作量預算而非精確序列模擬。若實際 frame 更大、發生重試或快照成本隨狀態變大，須重算。

完整研究在這組負載下：gyro 432,000 次、throughput 43,200 次、usage poll 28,800 次、VPN audit 7,200 次；加上其他事件與控制後共 513,305 筆 commit，快照預算 8,121 次。

## 4. 未校準係數

這些值是為了算出可檢查的數值、檢驗敏感度而選的假設；尚無資料支持其中任何一欄是 Pixel 10a 的典型值。

| 參數 | 低 | 示例情境 | 高 |
| --- | ---: | ---: | ---: |
| runtime／權限與時間檢查，mW | 0.5 | 2 | 5 |
| awake 相對 suspend 的額外功率，mW | 50 | 100 | 200 |
| gyro 感測器及 hub，mW | 2 | 5 | 10 |
| 每個資料事件處理，mJ | 0.02 | 0.1 | 0.5 |
| 每次 throughput 查詢，mJ | 0.1 | 0.5 | 2 |
| 每次 UsageEvents 查詢，mJ | 1 | 5 | 20 |
| 每筆加密 durable commit，mJ | 0.5 | 2 | 8 |
| 每次完整加密快照，mJ | 2 | 10 | 40 |
| VPN 常駐非轉送工作，mW | 0.5 | 2 | 8 |
| 每次 VPN 稽核及 WorkManager 作業，mJ | 1 | 5 | 20 |
| VPN 轉送，mJ／MB | 10 | 50 | 200 |
| VPN 額外封包成本，mJ／packet | 0.001 | 0.005 | 0.02 |
| 每次連線建立或 keepalive 處理，mJ | 1 | 5 | 20 |
| 限速期間 limiter 額外處理，mW | 0.05 | 0.2 | 1 |

runtime 項包含服務、25 秒權限核對、每分鐘時間檢查及一般管理；不含另列的 collector／儲存／VPN 成本。VPN 稽核項包括其事件處理及排程系統的額外工作，commit 加密另計。MB 與 packet 係數分別代表與資料量及封包數相關的不同成本，不能拿同一份總轉送能量各除一次後再相加。

本例把限速造成的 radio 能量差 `ΔP_radio_treatment` 設為 0，表示基線算式排除這個未知效應，**不代表限速沒有影響**。0.5 Mbps 是速率上限，不能據此推算傳輸量或聲稱省電；重傳、訊號品質、傳輸拉長、影音降畫質與使用行為都可能改變結果。上述效應也是研究關心的處置結果，應另外估計。

## 5. 哪個係數最影響結果

示例情境的 11.19 Wh 裡，共用額外喚醒占 9.60 Wh，感測器本身占 0.60 Wh，加密 commit／快照合計約 0.31 Wh。這是所選參數的結果，不是已量得的占比。

其餘條件固定，只把 awake 增量功率改為 50／100／200 mW，每日結果變成 **6.51／11.40／21.18** 個電量百分點。因此持續 wake lock 的真實成本是第一個值得校準的參數。

其餘條件固定，只把實際 gyro 頻率改為 1／5／20 Hz，每日結果變成 **11.40／12.50／16.64** 個百分點。這只是 callback／儲存工作量敏感度；若感測器本身功率、CPU 頻率或 queue 壓力也增加，還需要修改對應係數。

若限速的 15 小時平均額外 radio 功率為 20／100 mW，五天再增加 0.30／1.50 Wh；平均每天再增加 **0.31／1.53** 個百分點。效應也可能為負，腳本接受有正負號的 radio 差值。

## 6. 重算與校準

```sh
python3 tools/estimate_study_energy.py
python3 tools/estimate_study_energy.py --json
python3 tools/estimate_study_energy.py --assumptions docs/pixel-10a-energy-assumptions.json
```

修改 JSON 可改變功能組合、功耗係數、實際頻率、流量與喚醒比例；腳本不修改 Android App 或研究設定。預設輸出會明示未校準狀態。這是針對本研究七個 collector 的工作負載試算，不是可自動解讀所有研究組態的通用耗能預測器。

取得 Pixel 10a 後，優先量測相同負載下的 awake／suspend 差值與完整研究，再逐項校準查詢、commit、snapshot 及 VPN 係數。Power Profiler 可取得支援 Pixel 的裝置功率資料，但它是整機資料，需以對照條件分離研究增量。SensorManager 回報的感測器功率也不能代替整個 App 的能量。[Android Power Profiler](https://developer.android.com/studio/profile/power-profiler)

Google Perfetto 的 Wattson 已列出 Tensor G4 的支援及 CPU policy／idle 對應，可作為取得真機 trace 後的 CPU 子系統估算路徑；仍需要實際頻率、idle 與 devfreq 資料，也不能直接補齊整機、儲存及感測器能量。本次試算沒有冒用這些曲線來聲稱已校準。[Perfetto 原始碼](https://github.com/google/perfetto/blob/main/src/trace_processor/perfetto_sql/stdlib/wattson/device_infos.sql)
