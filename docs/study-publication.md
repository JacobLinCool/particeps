# 更新正式五天研究

正式來源為 `researcher-tools/studies/phone-usage-five-day.json`。參與者一律從[參與者頁](https://jacoblincool.github.io/particeps/participant/)取得最新設定與操作單。

## 固定入口

- 設定：`https://jacoblincool.github.io/particeps/studies/phone-usage-five-day/study.partcfg`
- 操作單：`https://jacoblincool.github.io/particeps/studies/phone-usage-five-day/participant-start.zh-TW.pdf`

網址與檔名不加入日期、版本或摘要。頁面的下載按鈕使用 `cache: no-store` 重新取得檔案；失敗時提示重試，不使用之前保存的資料。GitHub Pages 部署與 CDN 傳播完成後才算新版可用，不能僅憑 push 宣告發布成功。

## 發布步驟

1. 編輯正式來源。修改研究內容時更新內部 `configuration_id`、簽發／到期時間，涉及同意內容時同步 `consent.document_version`。內部識別用於區分回收資料，不影響公開下載名稱。到期時間是匯入期限，研究仍從 Start 起算 120 小時。
2. 同步問卷範例、題目文件及操作單。問卷修訂不應連帶改變限速、收集或問卷排程；先比對來源差異。
3. 使用研究者 CLI 的 `canonicalize`、`sign`、`check-config`，以原研究金鑰簽署，在全新的暫存目錄輸出。`check-config` 明確指定可信公開金鑰、signer key ID、App versionCode 及目前時間；不得只信任檔案內自述的金鑰。私鑰不得加入版本庫或網站。
4. 驗證成功後將簽署檔複製至 `web/static/studies/phone-usage-five-day/study.partcfg`。若操作單有變，同步該目錄的 `participant-start.zh-TW.pdf`，逐頁確認中文與排版。
5. 在 `web/` 執行 `pnpm run check`、`pnpm run test`、`BASE_PATH=/particeps pnpm run build`。測試核對正式來源與簽署設定、固定網址、可信指紋及問卷內容。
6. 將相關檔案明確列出後 commit、push 至 `main`，等 Pages workflow 成功。以未登入方式從正式固定網址重新下載，確認內容與本次產物逐位元相同，並再次通過 CLI 簽章驗證。保留驗證紀錄。

歷史簽署設定與 Git 歷史用於解釋舊資料。原 RC17 日期交付 ZIP 是六題歷史快照，不隨固定網址更新，不應當作最新研究入口。

## 參與者更新行為

每次初次設定或重新安裝，均需從參與者頁重新下載並匯入 `study.partcfg`；手機原本存下的檔案不會自己更新。下載清單若顯示同名檔案，依最新下載時間選取。重新安裝 APK 本身不會自動取得研究設定。

已開始的研究使用原匯入設定，不自動改動實驗條件。若需要中途換版，先由團隊安排匯出、資料回收及重新入組，保留配置與安裝代碼對照；不可要求參與者直接解除安裝或清除資料。新舊問卷資料須依配置識別分析，不能將舊版缺題補成否定答案。
