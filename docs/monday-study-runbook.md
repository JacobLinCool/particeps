# 2026-10-05 五天研究執行手冊

本文件供研究團隊使用。開始日期固定為 2026-10-05；參與者使用自己的手機，團隊已確認 Android 14 以上。未驗證的型號與省電設定仍須在入組時逐機檢查。

## 交付版本

- App：使用 [參與者頁](https://jacoblincool.github.io/particeps/participant/) 提供的正式簽章版 `1.0.0-rc.17`。實際 versionCode、大小與 SHA-256 見 [交付版本表](monday-participant-handoff.md#每位受試者要收到什麼)；簽署研究設定要求最低 versionCode `41`。研究端保留同一份 Release APK、checksum、簽章與匿名下載驗證紀錄。
- 簽署研究設定：網站的「手機使用與日常活動研究」區塊提供 `.partcfg`，其原檔位於 `web/static/studies/phone-usage-five-day-20261005-r1/`。研究者為「黃貞穎老師團隊」，聯絡方式指向原邀請管道，不列私人電話或 Email。App 使用事件為必要、連續收集；120 小時；每日 gyro 時段與現有限速規則保留。本機 `output/monday-study/study-draft.json` 與 `study.canonical.json` 是研究者工作檔；參與者匯入 `.partcfg`。
- 分析工具：使用包含串流驗證與 `usage-report` 的本次版本。每次處理均保留原始密文、manifest 與 quality summary，不用舊 RC13 分析器。

本次簽署設定於第 3–5 天 17:00 邀請填答相同六題，回顧 12:00–17:00。題目見 [活動問卷](activity-substitution-survey.md)。若研究團隊改變日程，再產生新設定；不要覆寫已發出的設定或 QR。受試者拿到哪些檔案、如何匯入及交回，見 [交付清單](monday-participant-handoff.md)與[中文操作單](participant-start.zh-TW.md)。

## 正式開始前完成的事

1. 固定 APK 與設定 SHA-256、版本、研究者與同意內容、可填答日期、正式資料接收位置。以正式匯出公鑰對應的私鑰試讀一份端到端測試檔，不只比對金鑰名稱。
2. 由團隊以可取得的實體 Android 手機，從正式下載管道安裝 APK；不要只透過 ADB 安裝。確認 Play Protect 與使用情況存取權的實際授權流程。
3. 以明確同意的測試手機執行 App 切換、鎖屏、切換 Wi-Fi／行動網路、重開機與手動繼續、通知權限恢復、VPN 衝突與匯出。重開機／故障後仍需手動 Resume；不得用自動恢復掩蓋未驗證區間。
4. 檢查實際 App package 與事件時間、已套用限速條件、問卷邀請／提交，完成密文回收→驗證→Parquet→usage report。失敗要在第一名正式參與者開始前定位。
5. 測試回收位置的可用空間與檔案大小上限；研究者電腦保留密文、解密暫存、SQLite 與 Parquet 的空間。磁碟不足會明確失敗，不應反覆重試到耗盡剩餘空間。

本次尚未完成完整 120 小時實機耐久驗收。故障測試、合成資料驗證與模擬器結果不能替代五天實機證據；執行期間仍須持續觀察並每天回收。

## 每名參與者入組

| 檢查 | 留存結果 |
| --- | --- |
| 手機系統與安裝 | 品牌／型號、Android 版本、APK versionCode 與雜湊；確認正式簽章發布檔可正常安裝 |
| 個人日常限制 | 是否需使用另一個 VPN、工作設定檔、另一支手機；這些不由研究 VPN 自動涵蓋 |
| 設定與身分 | 匯入正式簽章設定，核對研究內容與簽章指紋；以研究代碼建立參與者對應表，限制存取 |
| Android 存取 | 通知及研究通知頻道可用、Usage Access 已授權、研究 VPN 可啟動；其他必要資料來源可用 |
| 省電限制 | 檢查此型號對 Particeps 的背景限制，參與者按團隊已驗證的操作方式設定；不承諾設定後程序永不停止 |
| 真正有資料 | 開啟並切換已知 App，開始試驗收集後匯出，研究者驗證對應來源事件；不能只看 App 總事件數 |
| 匯出交付 | 用參與者實際會用的方式交付一份成功完成的密文檔，研究者確認能解密並分析 |
| 操作理解 | 示範看到暫停提醒後開啟 App、修復存取、明確點 Resume；強制停止後需自行開啟檢查；完成匯出前不刪除 App 或資料 |
| Start 時間 | 記錄正式 Start 的日期與時間；測試研究與正式研究不可混算同一段 120 小時 |

若入組測試用另一份測試設定，必須完成或退出、先妥善保留測試匯出，再清理測試資料並匯入正式設定；不得把正式研究的開始時間用作可隨意重設的測試時鐘。

## 每日資料核對與回收

指定一名負責人及備援，依所有參與者相同的流程核對，避免不同條件收到不同強度的提醒。至少每日取得一次成功完成的加密匯出並在研究端實際驗證；是否另設自動上傳，必須寫入正式簽章設定與同意內容後再啟用。本次簽署設定沒有自動上傳，收件位置及每日回收時間仍需團隊通知。

每天核對以下項目：

- 最後一筆已提交資料時間與研究狀態；匯出成功但最後資料停在昨日，同樣需要處理。
- `usage_events.v1` 是否存在、來源 query coverage、可配對前景區間、截尾及缺口。零可配對使用時間不等於沒有使用。
- 實際已套用的限速／不限速資源 receipts 與 epoch；不可直接把每日 12–17 時視為限速成功。
- 問卷是否邀請、開啟、提交或逾期。缺答不自動等同沒有替代行為。
- 手機是否暫停、通知受阻、空間不足或遇到 VPN 衝突；先保存資料，再按正常存取／Resume 流程排除。
- 已收到密文的 SHA-256、大小、研究者驗證結果與備份位置。每次匯出是當下固定邊界，之後資料須在下次匯出取得。

資料可用性的門檻由研究團隊在查看效果前訂定，至少分開記錄 App 來源可觀測比例與處置執行比例。不要臨時把缺口當成使用減少，也不要只因完成五天倒數就把資料判為可分析。

現有設計固定第 1–2 天為 baseline、第 3–5 天午後限速，星期、疲勞與學習等時間效應可能和處置重疊；不能僅憑前後差異宣稱限速造成行為改變。使用 `usage-report` 比較全天及 before／during／after 時段時，應同時檢查 `traffic-conditions.csv` 的 runtime 已套用狀態、原始 receipts 與資料缺口，不把時段名稱當成處置證明。

## 研究端命令

研究者先從 [RC17 Release](https://github.com/JacobLinCool/particeps/releases/tag/v1.0.0-rc.17) 下載 `particeps_analysis-0.1.0-py3-none-any.whl` 與其 `.sha256`。本次 wheel 的 SHA-256 為 `02648dfb767a945d0f666670c3af78b0d5bc4cfe4580f82c3f363ca1c98902bd`；它是研究端工具，不交給參與者。

在下載目錄核對摘要，並使用 Python 3.11 以上建立新的分析環境。以下為 macOS／Linux 操作範例；`/secure/study` 請換成實際受保護的研究資料目錄。

```sh
shasum -a 256 -c particeps_analysis-0.1.0-py3-none-any.whl.sha256
```

摘要核對必須顯示 `OK`，再執行：

```sh
python3 -m venv /secure/study/analysis-env
. /secure/study/analysis-env/bin/activate
python -m pip install ./particeps_analysis-0.1.0-py3-none-any.whl
```

每日分析沿用這個環境。研究者金鑰格式見 [分析工具說明](../particeps-analysis/README.md)。

研究者私鑰檔案使用分析工具的 keyring 格式並限制為只有擁有者可讀寫；不要傳給參與者或放在公開網站。以下路徑為操作範例，應換成實際受保護的回收目錄。

```sh
particeps-analysis inventory \
  --workspace /secure/study/workspace \
  --local /secure/study/incoming

particeps-analysis materialize \
  --workspace /secure/study/workspace \
  --keys /secure/study/researcher-keys.json \
  --output /secure/study/datasets/day-1

particeps-analysis usage-report \
  --dataset /secure/study/datasets/day-1 \
  --output /secure/study/reports/day-1 \
  --timezone Asia/Taipei \
  --duration-hours 120
```

輸出目錄必須尚不存在；每天使用新目錄。每次 inventory 是所指定來源的完整快照，回收目錄須保留既有檔案，或在 `--local` 同時列出舊、新來源；不要只指定今天的增量檔案。驗證器會對同一實驗身分的相同 commits 去重，衝突或缺鏈則拒絕產出資料。`usage-report` 的完整欄位與推論界線見 [使用分析](usage-analysis.md)。

## 最後一天與完成回收

例如從週一 10:00 按 Start 起算 120 小時，會到週六 10:00 才截止；實際開始時段由團隊另行通知。暫停不延長截止，週五 17:00 也不是整份研究結束。若研究團隊需要另一種結束時間，須在簽署與入組前定案。

截止後另做一次最終匯出，確認包含完成狀態與最後資料；研究者成功驗證、產出分析表並完成備份後，再處理參與者手機上的清理。報酬依事前向參與者說明的規則辦理，不以臨時產生的資料品質門檻更改承諾。
