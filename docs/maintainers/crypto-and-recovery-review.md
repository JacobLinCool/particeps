# 加密與冷啟動恢復檢視

日期：2026-10-26 至 2026-10-27。基準：`0249bd52e1a6a87e428a6686fbcb2fa960c2b0df` 及本次工作目錄修改。
這是原始碼分析與隔離模擬器驗證。回報手機為 Samsung S24 Ultra、Android 16、One UI 8.5，
使用者描述第五天後停在「正在恢復研究執行狀態」；尚無該手機的 App 版號、實際研究設定、
儲存檔案或執行追蹤，因此不能將以下候選路徑當成已重現的個案根因。

## 結論與本次處理

最值得簡化的是**啟動畫面與完整歷史資料驗證的耦合**。現行快照雖能縮短狀態重播，
卻不會免除歷史資料的解密、解析與驗證。保留越多資料，冷啟動的工作越多。
加密、研究資料一致性驗證、當下狀態恢復，應分別評估其成本與必要性。

本次完成可直接套用於現有資料的改善：

- 每次掃描只建立一個解密 `Cipher`，每筆仍重新設定自己的金鑰、IV 和 AAD，並驗證 GCM tag。
  移除逐筆 `Cipher.getInstance` 的 provider 查找與物件建立；沒有合併密文、沿用 IV 或略過驗證。
- 原有那一趟掃描直接回報已處理檔案位元組，顯示整數百分比及等待時間。
  沒有為了計算進度再解密一次，也沒有估算剩餘秒數。
- 分開「確認研究設定」「檢查已儲存資料」「恢復研究執行狀態」「準備研究畫面」。
  資料掃描 100% 只代表該階段完成，後續初始化仍受同一個 readiness 邊界控制。
- 冷恢復在紀錄邊界檢查 coroutine 取消；取消會向上傳遞並關閉尚未發布的 runtime assembly，
  不會被包成資料毀損、刪除檔案或自動重新開始。

這些修改維持現有 layout 3、key alias、AAD、IV/tag、commit digest 與匯出格式。
不需要資料轉換或清空 App；實際發佈仍須使用既有 application ID 與簽章才能原地更新。
效能變化尚未在回報手機量測，完整掃描仍然是線性成本。

## 每個機制究竟保護什麼

| 機制 | 原始碼中的實際用途 | 判斷 |
| --- | --- | --- |
| 本機 Android Keystore AES-256-GCM | 分別加密 commit frame、runtime snapshot、pending input；每個物件只加密一次 | 保留。這是現行威脅模型對本機研究資料的保護，不是三層包住同一個 payload |
| snapshot | 儲存已提交狀態的快取，讓後續 commit 能恢復當下狀態 | 保留。它不是歷史 observation 的完整副本 |
| pending input | 在資源切換前保存尚未提交的因果輸入，提交後才清除 | 保留。這個短暫重疊服務程序中斷恢復，不能當成冗餘加密刪掉 |
| commit／observation／checkpoint SHA-256 | 綁定內容、批次、鏈結與重播狀態，供跨語言研究資料驗證 | 保留現行協定。hash 不是加密；也不是可證明裝置或參與者身分的簽章 |
| 設定 Ed25519 簽章 | 驗證研究設定的簽章與內容，包含研究者匯出公鑰 | 保留。不是每筆資料都執行的冷掃描成本 |
| 匯出 AES-GCM + HPKE | 新的 AES key 串流加密 bundle；HPKE 只加密這個 32-byte key | 保留。接收者持有私鑰才能開啟匯出；本機 key 不交給研究者 |
| 每筆建立新的解密 Cipher | 每個 frame 都重新取得相同 transformation 的物件 | 已移除，改為單一掃描內重用物件 |
| 主畫面前全歷史驗證 | 每次 cold open 驗證所有 retained commits，即使 snapshot 已是最新 | 成本最高的設計候選；要改變的是驗證時機與信任契約，不能僅刪掉迴圈 |

本機加密是威脅模型的選擇，不能僅因 Android 已提供檔案加密就宣稱絕對必要，
也不能宣稱兩者保護範圍完全相同。移除應用程式層加密，會放棄目前「取得研究檔案但沒有
裝置 Keystore key 時仍無法讀取內容」的邊界，並且需要處理既有密文。本次保留此邊界。
[Android Keystore 文件](https://developer.android.com/privacy-and-security/keystore) 說明 key material
不進入 App process，操作經系統程序執行；因此每筆一次 Keystore 操作可能有固定成本。
這是值得量測的成本來源，不能據此推算 S24 Ultra 的每筆延遲或推斷其硬體實作。

匯出的混合加密符合公鑰交換與對稱加密分工；HPKE base mode 本身不提供發送者身分認證，
不能把 bundle 的可解密性解讀成參與者資料必然真實。
[Tink Hybrid Encryption 文件](https://developers.google.com/tink/hybrid)。

## 為什麼第五天更容易看起來卡住

目前路徑為：

```text
ApplicationStartup（IO 執行緒，尚未發布 Ready）
  → StudySessionManager.activateLocked
  → ExperimentRuntime.initialize
  → RuntimeRecovery.loadLocked
  → EncryptedExperimentStore.loadRuntime
      → 解密及驗證 snapshot
      → 從 snapshot.retainedFromCommit 掃描到檔尾
          每個 retained frame：GCM 解密 → JSON 解析 → 內容／鏈結驗證
          snapshot 之後的 frame：另外套用狀態變更
      → 解決 pending input，必要時更新 snapshot
  → 安全恢復及平台排程
  → 發布 Ready，開放主畫面
```

冷啟動時間可拆成 `固定初始化 + Σ(Keystore 操作 + 解碼／內容驗證) + 狀態恢復／排程`。
快照只減少後半段狀態重播，不消除前面的 Σ。每個 commit 的 payload 也影響成本，
所以資料位元組百分比不等於已消耗時間百分比。

repository 的五天設定使用 120 小時、8 GiB 上限，並未啟用自動上傳；不能假設每天資料會移除。
靜態呼叫搜尋也未找到正式流程呼叫 `evictThrough`。在持續收集的假設下，
現行 gyro 5 秒 commit window 等週期來源可累積約十萬級 commit；這是設定推估，
不是該手機的資料量。以往逐 sample 提交的估算不能直接套用。

沒有發現「第五天一定卡死」的專屬分支。畫面已出現表示 UI 曾成功繪製，
但單張截圖不能區分緩慢掃描、Keystore/I/O 停滯、系統資源不足或後續恢復等待。

## 可以再簡化什麼，以及代價

1. **先量測各段成本，避免繼續為不存在的瓶頸加設計。**這次階段與百分比會以不含研究內容的
   coarse log 輸出，可分辨資料檢查是否前進、是否卡在最後恢復或排程。
   在目標手機還需要區分 Keystore、解碼／hash、讀取與狀態恢復時間，才有速度結論。
2. **最有價值的後續方向是縮小啟動必須處理的範圍。**例如先安全恢復必要狀態，
   在讀取／匯出資料前完成歷史驗證；但這會改變「最新 snapshot 也不得掩蓋舊資料損壞」的
   現有契約。`RetainedStateTransition` 也依賴同一掃描取得目前狀態開始時間，不能只刪前段掃描。
   必須明確決定何時可發布 Ready、何時可恢復收集，以及背景發現損壞後如何處理。
3. **Keystore 包裝資料金鑰、軟體 AES 處理大量 frame**能減少跨系統加密操作，
   但會讓可匯出的資料金鑰存在 App 記憶體，新增 key lifecycle 與檔案格式／既有資料處理。
   在尚無目標裝置量測前，這會增加設計複雜度，本次未引入。
4. **不再建立未驗證的平行真相。**「已驗證」boolean、只憑 mtime/長度跳過驗證、
   遇到壞資料時換較弱 reader，都無法維持目前完整性保證。
   同理，將已上傳資料自動刪除必須先滿足研究留存、匯出與已確認水位契約，不能當作啟動捷徑。

`EngineCommitIntegrity.calculate` 已對 immutable value 快取 digest；
同一個 commit 上多次 `verify` 通常是比較，不是反覆 hash 全部內容。
Keystore handle 也已在 open store 內快取。因此這兩處沒有再新增另一層快取。

## 進度與取消的精確契約

- 分母是該次掃描已捕捉的 segment 長度總和；分子只在完整 frame 驗證及消費後推進。
  掃描成功修剪未提交的 torn tail 時，該已處理尾端也計入原分母。
- 只在整數百分比變化時通知，至多 101 次；不足完成的掃描不會因浮點取整顯示 100%。
- snapshot 指向的鏈結邊界確認成功後才發布掃描完成；保留紀錄或邊界驗證失敗不會發布 100%。
  完成掃描後仍可能有 pending input、checkpoint 寫入及狀態／平台初始化。
- 參與者 UI 只接收 stage 與百分比，不接收 byte count、event count、研究配置、timer、epoch、digest
  或內部錯誤碼。等待時間使用單調時鐘，代表目前啟動畫面顯示期間的實際等待。
- 2.5 秒內的短啟動保留原本簡潔畫面；較長等待才顯示進度與說明。
- 取消只在紀錄邊界生效，不能保證中止一個已阻塞的系統 Keystore 呼叫。
  使用者關閉某個畫面也不會取消 process-owned 初始化，進度不是新的停止研究按鈕。

Cipher 的重新初始化遵循 [Android Cipher API](https://developer.android.com/reference/javax/crypto/Cipher)：
每個 frame 都 `init(DECRYPT_MODE, key, GCMParameterSpec(...))` 並提供該 frame 的 AAD。
實例只存在該次 reader 內，並行匯出或另一掃描各用自己的實例。

## 驗證

Android 測試使用本機唯讀 AVD clone，不是回報手機。沒有使用參與者的研究資料進行測試。

結果：279 項 JVM 測試、42 項 Android storage 測試及 3 項 Android App 測試通過；
Android 環境為 API 34。Debug APK 建置成功，lint 無 error，仍有 9 則與此次修改無關的
ABI、未使用資源與 UseKtx 警告。`git diff --check` 通過。繁中畫面使用 37% 的測試 fixture，
不是參與者手機的真實進度；其虛擬測試時鐘與真實等待時鐘分開，因此截圖等待時間為 0 秒。

- JVM：model、experiment-runtime、study-application、App 的相關完整測試套件。
- Android storage：`EncryptedExperimentStoreTest`，含真實 Android Keystore 加解密、多 segment、
  最新快照下的歷史損壞、pending、torn checkpoint/tail、取消，以及進度單調且不改變既有 frame bytes。
- Android App：`StartupProgressAndroidTest` 與 `ApplicationStartupAndroidTest`，檢查繁中百分比、
  accessibility range、等待時間、後續準備階段與 Android main looper 回應。
- 建置 debug App／instrumentation APK，執行 Android lint 與 `git diff --check`。

可重跑命令（依本機調整 `ANDROID_SERIAL`；ABI 設定對應此處的 arm64 模擬器）：

```sh
./gradlew :core:model:test :core:experiment-runtime:test :core:study-application:test \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug \
  --offline -Pkotlin.compiler.execution.strategy=in-process -PinstrumentedTestAbi=arm64-v8a
ANDROID_SERIAL=emulator-5562 ./gradlew :core:storage:connectedDebugAndroidTest \
  --offline -PinstrumentedTestAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=cool.jacoblin.particeps.core.storage.EncryptedExperimentStoreTest
ANDROID_SERIAL=emulator-5562 ./gradlew :app:connectedDebugAndroidTest \
  --offline -PinstrumentedTestAbi=arm64-v8a \
  -Pandroid.testInstrumentationRunnerArguments.class=cool.jacoblin.particeps.StartupProgressAndroidTest,cool.jacoblin.particeps.ApplicationStartupAndroidTest
```

相關實作入口：

- `core/storage/.../EncryptedExperimentStore.kt`：`loadRuntime`、`replayAfter`、`scanFrames`、`decryptCommit`。
- `core/model/.../EngineCommitIntegrity.kt`：digest 快取與驗證。
- `core/export/.../ResearchExport.kt`：`encrypt` 的 content key 與 HPKE key wrapping。
- `core/study-application/.../StudyApplication.kt`：`StartupProgress` 與 `activateLocked`。
- `app/.../CollectorDashboard.kt`：`StartupPatience`。
- [威脅模型](../threat-model.md) 與 [儲存／冷啟動設計](../system-design.md#snapshots-and-cold-start)。
