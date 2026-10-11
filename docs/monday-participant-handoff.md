# 週一研究：受試者交付與入組核對

本文件供黃貞穎老師團隊使用。參與者說明請使用 [繁中操作單](participant-start.zh-TW.md)，不要把本文件或分析手冊整份交給參與者。

## 每位受試者要收到什麼

1. `particeps-v1.0.0-rc.17.apk`：RC17 App 正式簽章發布檔。
2. `study.partcfg`：已簽署、以「黃貞穎老師團隊」為研究者的研究設定。
3. `participant-start.zh-TW.pdf`：手機可閱讀的中文操作單，包含可信管道核對用的簽章指紋。
4. 團隊的開始通知與資料交回通知：指定哪個時段按「開始研究」、每天何時交回、交到哪裡，以及聯絡與報酬領取方式。

請以網站的固定入口提供最新設定與操作單，不要轉傳先前存下的交付包。RC17 Release 中日期為 20261005 的 ZIP 保留原六題設定，只供歷史核對。

| 檔案 | 取得方式 |
| --- | --- |
| App | [RC17 APK](https://github.com/JacobLinCool/particeps/releases/download/v1.0.0-rc.17/particeps-v1.0.0-rc.17.apk)，versionCode 44 |
| `study.partcfg` | [最新簽署研究設定](https://jacoblincool.github.io/particeps/studies/phone-usage-five-day/study.partcfg) |
| `participant-start.zh-TW.pdf` | [最新操作單](https://jacoblincool.github.io/particeps/studies/phone-usage-five-day/participant-start.zh-TW.pdf) |

目前七題設定為 120 小時；App 使用紀錄為必要來源；前兩天不限速，第 3–5 天 12–17 時雙向各 500 kbps；問卷於第 3–5 天 17:00 邀請。更改已簽署內容，必須重新簽署並保留內部版本識別；公開網址及檔名維持固定。沿用同一簽署金鑰時，簽章指紋保持相同。

研究團隊不列私人電話或 Email。設定中的聯絡方式為「請透過邀請您參與研究的管道聯絡研究團隊。」因此邀請管道必須有人值守，受試者遇到安裝或回收問題時才能得到處理。

## 發布後的下載與設定入口

參與者使用 [公開參與者頁](https://jacoblincool.github.io/particeps/participant/) 的「下載 App」取得 RC17，並在「黃貞穎老師團隊：手機使用與日常活動研究」區塊取得研究設定檔與中文 PDF。設定檔的網址與下載檔名固定為 `study.partcfg`；每次設定或重新安裝均須重新下載並匯入。重新安裝 APK 不會自動替換研究設定。

設定與操作單存放於 `web/static/studies/phone-usage-five-day/`。更新與發布方式見[發布流程](study-publication.md)；每次發布後都須下載網站上的實際檔案驗證。

團隊也可直接傳送 APK、`study.partcfg` 與 PDF；受試者從 Particeps 的「選擇研究設定檔」載入，不需要帳號或 QR。既有兩份公開 QR 是內部試跑設定，其中一份把 App 使用資料設為可選；不要將舊 QR、舊 `join.txt` 或舊 `.partcfg` 交給正式受試者。

網站也提供「顯示 QR code」按鈕：每次展開都重新取得並驗證目前設定，產生 App 可掃描的研究加入代碼。公開設定網址與檔名保持固定；代碼內綁定當次設定摘要與簽章指紋。研究更新後須收起再重新開啟代碼，舊截圖不會自動更新。同一支手機瀏覽網站時，使用下載設定檔方式。

不要交付研究者私鑰、分析工具、原始 `study.json`、效應分析報表或整個研究者工作目錄。`.partcfg` 才是 App 接受的研究設定；原始 JSON 是研究者的工作檔，並包含完整研究規則。

## 入組身分與時間

目前使用共用設定，`assigned_participant_id` 為空。App 不要求帳號；每次成功匯入會產生一組新的安裝代碼。正式開始後，在「研究說明與我的資料」抄錄安裝代碼，與團隊的匿名研究代碼對照。姓名／聯絡名冊另行保管，不寫進公開網址或設定檔。

使用 [入組紀錄表](study-templates/enrollment-register.csv) 記錄每部手機的驗收。研究代碼與安裝代碼必須逐一對上。重新匯入產生新代碼時保留舊記錄，不能直接覆蓋同一列。

設定 ID 的日期不會讓研究在週一自動開始。可以事先安裝、匯入與授權，但正式 Start 時段須由團隊通知；開始鍵按下後才起算 120 小時。測試研究應另用測試 instance，先保存測試匯出，再依 App 正常流程清理。不可把正式 Start 當成可隨意重設的測試。

Android 14 以上只是最低條件；本設定還要求陀螺儀、Usage Access、通知與研究 VPN。每部手機都要實際完成授權及開始前檢查，遇到感測器缺失或裝置管理限制須當場處理。

## 每日與最終回收

`upload` 為空，沒有自動上傳。團隊須指定一個實際收件位置、每天回收時間與負責人，並在原邀請管道通知受試者。不能只寫「匯出即可」。

匯出檔名只有 experiment ID 與秒級時間，不能用來判定是哪位參與者；不同手機同秒匯出也可能同名。接收時依研究代碼分資料夾，以不可覆寫方式保存每次檔案，記錄檔案摘要與安裝代碼。

[回收驗證紀錄表](study-templates/export-receipt-register.csv) 是每日驗證紀錄空白表。收到 `.partexp` 後實際執行密文驗證、materialize 與 usage-report；確認安裝代碼、最新資料時間、來源涵蓋率與研究狀態，再回覆受試者已完成該次回收。截圖或檔案存在都不能替代這一步。

完整命令、缺口判讀與完成回收條件見 [執行手冊](monday-study-runbook.md)。不要因為提前按「完成研究」就把不足 120 小時的資料列成已完成。

## 發送前最後核對

- APK 符合 RC17 Release checksum，網站 `.partcfg` 通過簽章驗證且與正式研究來源一致；簽章設定要求最低 versionCode 41。
- 受試者操作單與這次設定使用相同的研究團隊名稱及 signer fingerprint。
- 安裝檔由實際邀請管道可下載；至少一部實體手機已走過下載、安裝、檔案匯入、授權、收集、匯出及研究者分析。
- 團隊在入組前通知開始時段、每日收件時間／位置、聯絡值守與報酬領取方式。
- 本次模擬器測試不能替代 120 小時實機耐久證據，亦不能替所有廠牌的省電與安裝策略背書。
