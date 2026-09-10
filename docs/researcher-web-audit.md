# 研究者網站設計與功能涵蓋審查

> 本文保留修正前的審查證據。R01–R13 已於本次改進實作處理，對照表見文末；實際驗證結果另列。

## 修正後狀態

R01–R13 與三項補充問題已完成實作處理。新增獨立每日研究總覽與原生 WebMCP，
並由同一份設定驅動表單、驗證、模擬與時間軸。322 項測試、兩條瀏覽器端到端流程、
Kotlin 設定驗證及正式建置均通過。完整修正對照與驗證限制見文末。

## 修正前結論

網站有良好的視覺與基本流程基礎，但目前還不能視為完整、可靠的研究者工作台。一般的被動資料蒐集、基本參數、簽署與檔案交付已有實作；問卷、進階研究條件、既有研究的延續，以及正式資料分析的銜接仍有重要缺口。

最需要優先處理的是研究設定的正確呈現與操作可靠性。此次實際重現新增 collector profile 失敗、盲化確認缺失卻顯示可簽署、限速錯誤無法定位，以及金鑰備份後未保存草稿直接遺失。匯入專案自己的五天研究時，六題問卷與三條活動規則仍在 JSON 中，表單卻沒有顯示題目、只顯示第一條活動規則；簽署與解密公鑰也已換成分頁的新金鑰。

本報告評估的是本機工作目錄，基準 commit 為 `ef65a73`，不是公開網站部署版本。這是原始碼與任務操作審查，並非研究人員的實證可用性測試。下列優先度反映核心任務受阻或錯誤研究的風險，不代表已發生真實研究資料損失。

## 優先發現

### R01｜高：新增 collector 設定檔實際失效

- 任務／準則：設定同一來源的不同採樣率；操作可靠性、系統狀態可見。
- 觀察：啟用「手機移動」後按「新增設定檔」，畫面仍只有 `continuous`，沒有可見錯誤提示。瀏覽器記錄 `DataCloneError: Failed to execute 'structuredClone' on 'Window'`。
- 證據：[draft.svelte.ts:524](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/draft.svelte.ts#L524) 對 reactive configuration 內的 `collector.profiles[0].config` 呼叫 `structuredClone`，在實際瀏覽器拋錯。
- 影響：無法透過網站新建高低頻 profile，再用條件切換；有按鈕不代表功能已完成。
- 建議：以明確的資料快照複製 profile，驗證真實瀏覽器中的新增、編輯與切換流程，並讓失敗可見。
- 信心：高；瀏覽器重現及程式碼確認。

### R02｜高：匯入既有研究會默默更換金鑰

- 任務／準則：修改並延續既有研究；符合使用者心智模型、錯誤預防。
- 觀察：頁面自動產生兩組金鑰；匯入五天研究後，簽署及 HPKE 公鑰都變為本分頁金鑰，原自訂 `demo-signer-2026`、`demo-hpke-2026` 名稱仍保留。成功提示只有檔名，沒有金鑰變更說明。
- 證據：[+page.svelte:79](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/+page.svelte#L79)、[draft.svelte.ts:626](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/draft.svelte.ts#L626)、[匯入後的提示:247](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/+page.svelte#L247)；瀏覽器讀到的 canonical JSON 與原範例公鑰不同。
- 影響：若研究者以為只是修改文案，重新發佈後的指紋與資料解密金鑰會改變；原私鑰不能解密新金鑰所加密的資料，自訂 ID 還可能形成同名異 key。這是再發佈後的風險推論，不是既有資料已被改寫。
- 建議：明確區分「延續既有研究」與「以此建立新研究」。延續流程應核對原公私鑰；任何換 key 都要呈現原本與新指紋及影響。缺少私鑰應是清楚的待辦。
- 信心：高；瀏覽器與既有測試皆確認行為。

### R03｜高：進階條件被誤顯示，額外活動規則不可見

- 任務／準則：審查匯入研究的觸發條件；忠實呈現系統狀態、錯誤預防。
- 觀察：每項 intervention 只找第一條 occurrence。五天研究的 `activities-day-3/4/5` 都在 JSON 中，表單卻只顯示 day-3；該規則的研究日時窗也沒有編輯欄位。其他合法條件還會被顯示成另一種條件：未識別的 resource condition 統一回傳「研究進行中」，sequence／window threshold trigger 顯示成 event match。
- 證據：[InterventionEditor.svelte:99](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L99)、[觸發類型映射:250](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L250)、[ResourceAutomationEditor.svelte:45](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/ResourceAutomationEditor.svelte#L45)、[選單覆寫:120](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/ResourceAutomationEditor.svelte#L120)。
- 影響：研究者不能依表單審查完整研究邏輯；切換選項可能整組替換原條件。**匯入 parser 並未把這些規則丟掉，問題在呈現與編輯。**
- 建議：逐條顯示全部 automations；未支援的型別也應顯示真實類型與完整摘要，不能冒充另一個可編輯類型。之後補上 predicates、sequence、threshold、布林組合、guard、cooldown 與 clocks 的編輯。
- 信心：高；多 occurrence 遺漏已實測，錯誤類型映射由程式碼確認。

### R04｜高：問卷編輯器不足以涵蓋現有研究

- 任務／準則：建立、審閱研究問卷；功能完整性、辨識優於記憶。
- 觀察：新增問卷固定是一題選填短文字、上限 200 字；表單只能編輯第一題 short_text 的文字。Android 支援多題、量表、單選、多選、必填與多語。匯入現有六題活動問卷後，因第一題是 multiple_choice，表單連題目文字都沒有。
- 證據：[InterventionEditor.svelte:85](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L85)、[問卷編輯區:225](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L225)、[Android 題型定義:187](https://github.com/JacobLinCool/particeps/blob/ef65a73/core/study-definition/src/main/kotlin/cool/jacoblin/particeps/core/definition/StudyConfiguration.kt#L187)、[實際問卷畫面:242](https://github.com/JacobLinCool/particeps/blob/ef65a73/app/src/main/kotlin/cool/jacoblin/particeps/SurveyActivity.kt#L242)。
- 影響：研究者無法只用網站完成專案現有的問卷，也無法完整審閱匯入內容；須在站外編輯 JSON。
- 建議：支援全部題型、多題增刪排序、選項、必填、限制與翻譯，並提供完整問卷預覽。
- 信心：高；實測與 Android 實作交叉確認。

### R05｜高：隨機問卷排程隱藏了真正決定研究的參數

- 任務／準則：規劃隨機 EMA；使用者控制、狀態可見。
- 觀察：選擇「隨機當地時段」後，只出現整項研究提示上限。實際建立值固定為 09:00–12:00、每窗 1 次、每日最多 1 次、最短間隔 60 分鐘；沒有相應時窗及日內次數欄位。另一個「最多觸發次數」仍預設為 1，形成兩種上限但沒有解釋其關係。
- 證據：[InterventionEditor.svelte:142](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L142)、[random-window 表單:271](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L271)、[初始 occurrence 上限:76](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L76)。
- 影響：看不到實際問卷可能出現的時段，也無法配置多時窗、每日多次的研究；單改提示總數不足以理解實際觸發限制。
- 建議：完整呈現時窗、每窗／每日／全期次數與間距，說明多重上限；以規則摘要檢查設計。仍應維持不抽選參與者實際隨機時刻的界線。
- 信心：高；瀏覽器重現與程式碼確認。

### R06｜高：簽署顯示「沒有問題」，實際仍被隱藏前提阻擋

- 任務／準則：簽署研究及修正問題；錯誤辨識與復原、系統狀態可見。
- 觀察：填妥問卷但未勾盲化確認時，簽署頁顯示「沒有問題」且按鈕可按；按下只出現「簽署失敗，沒有寫出任何檔案」。回到研究頁勾選確認後，使用相同設定即簽署成功。
- 證據：[draft.svelte.ts:683](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/draft.svelte.ts#L683) 的盲化前提不在 schema issues 中；[StepSign.svelte:225](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepSign.svelte#L225) 只依 issues 與簽署金鑰決定可操作；[+page.svelte:197](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/+page.svelte#L197) 將失敗化約為通用訊息。
- 影響：核心交付任務停住，畫面卻沒有可採取的修正步驟。
- 建議：將所有簽署前提納入同一份待辦，直接跳到未完成的確認項；保留具體失敗原因。
- 信心：高；已完成失敗與成功的對照操作。

### R07｜高：保存金鑰後，未保存的研究草稿不受離開保護

- 任務／準則：中斷並恢復研究編輯；使用者控制、錯誤預防。
- 觀察：下載兩把測試金鑰並按「檔案已存好」，返回研究頁修改目的，再點頁首 Particeps；網站直接回首頁，沒有確認。返回工作台時研究已空白。該次研究 JSON 與新修改都沒有下載。
- 證據：[draft.svelte.ts:447](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/draft.svelte.ts#L447) 的離開風險只看未保存金鑰；[+page.svelte:94](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/+page.svelte#L94) 的兩種離開攔截依賴此條件。頁面沒有獨立的草稿保存動作；簽署頁 JSON 是唯讀／複製區。
- 影響：備份金鑰不等於保存研究，長篇研究配置可直接遺失。
- 建議：以研究內容是否有未保存變更判斷離開風險，提供明確的本機草稿保存與恢復流程，無須因此把私鑰寫入瀏覽器儲存。
- 信心：高；瀏覽器重現。

### R08｜高：名為「加入範例」的操作會覆寫既有研究規則

- 任務／準則：加入依 App 使用情形限速的範例；操作名稱符合結果、錯誤預防。
- 觀察：`addAppUseRule` 直接替換全部限速 cases，且將 usage collector 改為 required、全部 profiles 改成 15 秒並啟用原本 inactive 的 case/default。
- 證據：[TrafficShapingEditor.svelte:91](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/TrafficShapingEditor.svelte#L91)，尤其第 104–116 行。
- 影響：已有的研究日／當地時窗可能消失，資料蒐集排程也被改動。這不是單純加入一個獨立範例。
- 建議：明示替換範圍及依賴變更，呈現差異、提供復原；或將範例作為獨立新研究的起點。
- 信心：高（原始碼）；本次未另外操作此替換情境。

### R09｜中：新增功能的錯誤無法導回欄位

- 任務／準則：修正配置；定位錯誤、降低記憶負擔。
- 觀察：限速上傳上限填 0，簽署頁列出 `traffic_shaping.profiles.1.uplink_kbps`，點擊後仍停留簽署頁。「研究」步驟同時顯示沒有問題。
- 證據：[steps.ts:44](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/steps.ts#L44) 未把 `automations`、`traffic_shaping` 歸給 study，未知路徑回 sign；[NullableCapField.svelte:3](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/NullableCapField.svelte#L3) 沒有 schema path；[jump:180](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/+page.svelte#L180) 只尋找完全對應欄位。
- 影響：研究者須自行理解內部路徑並在長表單中找位置；進階規則錯誤也受相同路徑分類影響。
- 建議：補齊步驟歸屬、欄位 path、中文欄位名與 focus，將跨來源依賴錯誤轉為可操作待辦。
- 信心：高；限速情境已實測。

### R10｜中：第一步過長，任務分組與後續分析混在同一條流程

- 任務／準則：建立與反覆調整研究；資訊層級、流程效率。
- 觀察：研究內容、資料、介入、條件、預覽、JSON 模擬等 11 個主區塊都放在第一步。五天研究在 1280 × 720 視窗下，整頁高 12,065 px，約 17 個視窗；導覽只有「研究／金鑰／簽署／檔案／讀取」，沒有研究內章節索引。讀取則通常發生在數日後。
- 證據：[StepStudy.svelte:87](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepStudy.svelte#L87)、[steps.ts:37](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/steps.ts#L37) 及瀏覽器 DOM 幾何量測。
- 影響：基本資料與進階研究邏輯的導覽層級相同，研究者反覆找條件與預覽要大量捲動。影響程度仍需使用者測試校準。
- 建議：依研究任務拆出基本資料、蒐集、活動與條件、檢查發佈；提供區塊索引、狀態及研究摘要。資料檢閱可有獨立入口。
- 信心：中；結構與長度已確認，使用者負擔屬專家判斷。

### R11｜中：模擬與預覽不足以支撐完整研究檢查

- 任務／準則：發佈前理解行為；辨識優於記憶、狀態可見。
- 觀察：模擬入口要求手寫事件 JSON，失敗只有通用訊息；結果只在按模擬時更新，修改研究後沒有結果過期標示。參與者預覽只顯示標題、目的、資料類別與固定 VPN 說明，不含完整同意內容或問卷。
- 證據：[SyntheticTraceSimulator.svelte:16](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/SyntheticTraceSimulator.svelte#L16)、[模擬時間基準:43](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/lib/particeps/simulator.ts#L43)、[ParticipantStudyPreview.svelte:16](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/ParticipantStudyPreview.svelte#L16)。
- 影響：不熟 protocol 的研究者難以建立情境，也可能把過期結果或未模擬的 random-window 看作沒有觸發。局部預覽不能充當完整施測前驗收。
- 建議：提供合成情境範例與表單、具體錯誤、時間基準、規則時間線及過期標示；標明預覽範圍，補齊既有參與者文字與問卷預覽。完整真機試跑仍是獨立必要步驟。
- 信心：高（功能範圍與狀態生命週期由原始碼確認）；未實測所有 trace 類型。

### R12｜中：單檔讀取摘要混用不同事件計數

- 任務／準則：判斷回傳資料範圍；精確呈現、錯誤預防。
- 觀察：UI 用 `lifetime_data_event_count > event_count` 判斷 partial，並把前者當作所有事件序號的分母；但一個是 collector 資料事件累計，另一個包括 system 事件。
- 證據：[StepRead.svelte:243](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepRead.svelte#L243)、[分母顯示:353](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepRead.svelte#L353)、[bundle.ts:581](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/lib/particeps/bundle.ts#L581)。共享合法 fixture 即有 5 個總事件、1 個 collector lifetime 事件。
- 影響：切片完整性訊號可能缺失或產生不可比較的分母。單檔成功驗證也不能證明整個研究已收齊。
- 建議：以 commit 起迄及 durable head 呈現切片範圍，分列資料事件與全部事件，明示跨檔完整性尚待驗證。
- 信心：高（原始碼與 fixture）；本次未用真實參與者資料驗證。

### R13｜中：從發佈到正式分析的交接資訊不足

- 任務／準則：讓資料實際回來並進入分析；流程連續性。
- 觀察：自動傳送提供 endpoint／interval／metered；QR 明示須自行託管；讀取則止於單檔摘要與 JSON 下載。批次個人化、receiver 部署、取得密文、多 bundle 重組與 Parquet 都在外部工具，頁面只有一般研究者指南連結，沒有各步驟的具體交接。
- 證據：[StepStudy.svelte:300](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepStudy.svelte#L300)、[receiver/README.md:37](https://github.com/JacobLinCool/particeps/blob/ef65a73/receiver/README.md#L37)、[StepRead.svelte:428](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/StepRead.svelte#L428)、[particeps-analysis/README.md:46](https://github.com/JacobLinCool/particeps/blob/ef65a73/particeps-analysis/README.md#L46)。瀏覽器 reader 上限為 32 MiB；完整分析要求完整 commit 鏈。
- 影響：填 HTTPS URL 不等於完成 receiver。預設 receiver 每次部署綁定一個 configuration SHA 和 researcher key ID；改版或個人化需要相應安排。收到多份或大型資料時，研究者不容易知道如何取得經驗證的可分析資料集。
- 建議：在正確位置連到部署、批次 personalize 與 analysis；提供應交付的 digest／key ID、輸入檔案、試跑驗證及 inventory/materialize 指令。無須把離線分析引擎搬進瀏覽器。
- 信心：高；網站與外部工具實作交叉確認。

以上共 13 項主要發現：高 8 項、中 5 項。先修已重現的操作與狀態錯誤，再補完整研究設計功能。

## 功能涵蓋矩陣

「外部 JSON」表示必須先在站外編輯合法配置再匯入；網站的 canonical JSON 是唯讀與複製檢視，不是完整 JSON 編輯器。這個矩陣不以欄位數算百分比，因為漏一個關鍵觸發條件的影響遠大於漏一個一般欄位。

| 應用程式／研究工具能力 | 網站目前涵蓋 | 判定 |
|---|---|---|
| 研究名稱、目的、研究者／聯絡、同意文字 | 表單可設定 | 已涵蓋 |
| 招募有效期、每人研究時長、共同時區顯示 | 表單與時間圖示 | 已涵蓋 |
| 15 種 selectable collector | 全部有開關與說明 | 已涵蓋 |
| 各 collector 現有參數、required | 有專用控制，部分錯誤定位不足 | 基本涵蓋 |
| 多個 collector profiles | 有 UI，新增在瀏覽器拋錯 | 有故障，見 R01 |
| 每來源的研究日與當地時窗、時段外停用／換 profile | 有表單與排序 | 已涵蓋基本模式 |
| 指定 packages 或所有 App、雙向限速、多個限速 profiles | 有表單 | 已涵蓋基本模式 |
| 完整 resource conditions：latch、presence、held、threshold、all/any/not | 僅部分常用模式，其餘外部 JSON | 部分，見 R03 |
| 通知與基本介入文字、必要性、availability、總次數 | 有表單 | 已涵蓋基本模式 |
| 多題、量表、單選、多選、必填、多語問卷 | 只可建立一題簡答 | 嚴重不足，見 R04 |
| 一次、固定間隔、每天當地時間 | 有表單 | 已涵蓋基本模式 |
| random-window 多時窗與完整上限 | 大多數參數沒有控制 | 部分，見 R05 |
| event predicate、evaluation clock、sequence、window threshold | 只有 event identity 等簡化控制 | 部分，見 R03 |
| 同一活動的多條觸發、guard、cooldown | 匯入保留，但無完整表單 | 部分，見 R03 |
| 未指定代碼／單一 assigned participant ID | 有表單 | 已涵蓋 |
| roster 批次 personalize | CLI 支援；網站無對應流程 | 外部工具，見 R13 |
| 最低 App client version | 新建固定為 `1`；匯入可保留；無 UI | 配置能力缺口 |
| quota、HTTPS 自動傳送、間隔、計費網路政策 | 有表單 | 設定已涵蓋；部署交接不足 |
| 產生／匯入金鑰、簽署、JSON／partcfg | 有完整基本流程 | 匯入與簽署狀態有 R02、R06 問題 |
| 固定檔案的加入 URI／QR、指紋 | 本機產生；有託管說明 | 已涵蓋生成，託管在站外 |
| 參與者預覽 | 部分內容摘要 | 部分，見 R11 |
| Synthetic simulator | JSON trace、最後資源狀態／活動 | 部分，見 R11 |
| 單 bundle 解密與 JSON 下載 | 有，32 MiB 上限 | 基本涵蓋；R12 摘要待修 |
| 跨 bundle 重組、完整性 replay、typed Parquet | particeps-analysis 支援 | 外部工具，見 R13 |
| 參與者 start／pause／resume／complete／withdraw／delete | 在 Android 操作 | 網站不需重做遠端控制 |

15 種來源為 app_lifecycle、accelerometer、battery_state、temporal_context、gyroscope、ambient_light、proximity、screen_state、network_throughput、network_state、vpn_state、network_usage、usage_events、location、keyboard_touch。來源基準見 [CollectorApplication.kt:90](https://github.com/JacobLinCool/particeps/blob/ef65a73/app/src/main/kotlin/cool/jacoblin/particeps/CollectorApplication.kt#L90)。notification_events 已不是現行可選能力，不能算網站漏項；CSV 也不是現行分析工具已提供但網站漏接的功能。

## 值得保留的設計

- 基本視覺語言、欄位、圖示及步驟導覽一致。抽查 390 × 844 的研究頁時 document 寬度仍為 390，未出現整頁水平溢出；這不是所有裝置與局部控制皆已驗證的保證。
- 資料來源有記錄內容與能力限制，例如 app_lifecycle 明說只記本 App 畫面，避免誤認成所有 App 使用資料。
- 招募期限與每人時長分開，時區明示；數值多用 Hz、秒、GiB 等研究者可讀單位。
- required 與排程分開處理，說明背景排程延遲及 gyroscope 停用／降低頻率的差別。
- 私鑰、需歸檔 JSON、給參與者的 partcfg 分組，金鑰下載與已保存確認分開。
- 基本欄位驗證能阻止簽署；編輯後舊簽署失效。QR 綁定確切檔案 digest 與指紋，明示不可重新導向。
- 合法匯入資料有嚴格的 canonical／schema 驗證，複雜問卷與 automations 並未因表單簡化而被 parser 丟棄。這個資料層基礎應保留。
- 模擬共用 compiler／reducer，且不抽選參與者實際 random-window 時刻；網站不應新增這種抽選預覽。

## 其他值得排入後續工作的項目

| 項目 | 證據與處理方向 |
|---|---|
| 最低 client version 不透明 | [types.ts:7](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/lib/particeps/types.ts#L7) 新建固定 `1`。顯示適用版本並允許設定；本次未斷言任何特定舊 App 必然接受新配置。 |
| 工具／資料版本不相容只回一般錯誤 | [bundle.ts:436](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/lib/particeps/bundle.ts#L436) 要求 registry digest 一致。宜提供可理解的版本診斷與對應工具入口，不需加入舊格式 fallback。 |
| 移除介入可能刪掉其他介入共用的 survey | [InterventionEditor.svelte:115](https://github.com/JacobLinCool/particeps/blob/ef65a73/web/src/routes/researcher/InterventionEditor.svelte#L115) 未檢查其他引用即刪 survey。應保留仍被引用的問卷；此為程式碼確認，未另行操作共用情境。 |

## 建議執行順序與驗收標準

1. **先修正可靠性。** 新增 profile 能在瀏覽器成功；任何匯入金鑰變動可見；未完成的簽署前提可定位；所有可編輯設定錯誤都能導回欄位；有未保存內容時可安全離開／恢復。
2. **讓現有研究在表單中完整可見。** 以五天研究為驗收樣本：六題全部可檢閱，三天活動規則全部可見，時窗／條件不被錯誤標記。每一項可簽出的設定都應有忠實摘要。
3. **補齊研究設計能力。** 優先完整問卷、random-window、multi-occurrence 與 guard/cooldown；再補進階條件編輯。若暫時只有外部 JSON 路徑，應在介面明示能力範圍。
4. **重整資訊架構與交付。** 為研究內區塊建立導覽，提供建立研究、發佈試跑、讀取分析的清楚入口；銜接 receiver、批次個人化及離線分析。
5. **以目標研究者驗證。** 請不同技術熟悉度的研究者完成「建立基本蒐集」「匯入／修改六題三天問卷」「發佈並分析多個回傳檔」；記錄完成率、錯誤研究設定、所需協助與復原能力。視覺偏好應放在核心任務驗證之後。

## 修正前的驗證紀錄與限制

本節記錄初次審查、尚未修正 R01–R13 時的基準。修正後的 322 項測試與瀏覽器驗證見文末「改進驗證」。

| 檢查 | 結果 |
|---|---|
| `npm run check` | 0 errors、0 warnings |
| `npm test` | 19 個檔案、236 項測試全部通過 |
| `npm run build` | 成功；有單一 chunk 超過 500 kB 的大小提示，未據此推論效能不合格 |
| 本機瀏覽器，正體中文主流程 | 新建、蒐集、profile 新增、問卷、random-window、錯誤列表、盲化確認、簽署、檔案與 QR、草稿離開 |
| 既有五天研究 | 使用現有 CLI canonicalize 後匯入；核對六題、三條 occurrence、公鑰及限速錯誤 |
| 桌面／窄螢幕 | 1280 × 720 主操作；390 × 844 抽查與還原 viewport |
| 未驗證 | 公開部署版本、真實招募／receiver 連線、Android 真機新流程、真實資料、多瀏覽器與完整輔助科技測試 |

當時的 236 項測試通過不代表瀏覽器操作已全部驗證。修正前的 draft 測試在 Node／SSR 下執行，配置是普通物件；瀏覽器的 Svelte `$state` 則包成 Proxy，所以原本的 profile 複製測試會通過，實際按鈕仍拋出 R01 的錯誤。

初次審查時，pnpm 啟動入口因本機版本嘗試重新整理 dependencies 而在無 TTY 環境中停止；改用 npm 執行同一份 package scripts，沿用既有 node_modules，沒有重裝依賴。Playwright CLI 的套件取得受網路限制，因此實際操作改用 Codex 的瀏覽器工具。上述環境狀況不列為產品缺陷。

初次審查階段未新增自動測試或修改產品程式。修正後的操作已收錄於 [研究者主流程測試](../web/e2e/researcher-flow.mjs)與[研究者改進測試](../web/e2e/researcher-improvements.mjs)，可依文末步驟重現。測試使用獨立瀏覽器與公開 fixture。讀取／receiver／分析的部分以實作與 fixture 為證據，不能當作已完成真實資料端到端演練。

## 審查計畫與範圍

- 日期：2026-09-11。
- 產品：Particeps 網站的 `/researcher` 研究者工作台；首頁及參與者預覽只檢查與研究者任務的銜接。
- 使用者：設計、發佈及分析 Android 行動資料蒐集研究的研究人員，包含不熟悉 JSON、金鑰與部署的研究人員。
- 範圍：目前工作目錄的網站程式、Android 與 protocol 的研究設定能力、研究者文件，以及本機網站操作。教學站不是主要審查對象；不以公開網站部署版本或真實參與者資料作驗證。
- 任務：理解前置條件；建立金鑰與研究；設定蒐集器、排程、問卷與介入；檢查參與者體驗及模擬；匯入、簽署、發佈；解密、檢閱並銜接資料分析。
- 成功標準：研究者能識別前置條件、配置應用程式支援的研究能力、辨識設定錯誤與限制、產生可供應用程式使用的檔案，並理解後續資料處理流程。
- 評估準則：系統狀態可見、符合研究任務心智模型、辨識優於記憶、一致性、錯誤預防與復原、流程連續性、資訊層級及操作優先順序。
- 嚴重程度：高＝核心任務受阻或容易產生錯誤研究；中＝可完成但有顯著學習、繞道或誤解成本；低＝局部效率或呈現問題；待驗證＝證據不足以定案。
- 證據：可定位的原始碼、現有檢查／測試、可重現的瀏覽器操作；區分觀察、影響推論及建議。
- 產出：本報告，包括功能涵蓋矩陣、按優先度排列的發現、優點、建議與驗證限制。

## 修正對照

| 問題 | 實作處理 |
| --- | --- |
| R01 設定檔新增崩潰 | 複製 Svelte state snapshot，瀏覽器實測新增／移除設定檔。 |
| R02 匯入金鑰被替換 | 保留匯入公鑰與 ID；相符私鑰匯入、錯配拒絕、明確的換鑰預覽。 |
| R03 條件與觸發器覆蓋不足 | 十種條件、五種 trigger 的結構化編輯器，所有 occurrence、guard、clock、cooldown 與上限。 |
| R04 問卷內容不完整 | 四種題型、全部題目／選項／限制／翻譯；共用問卷與參照保護。 |
| R05 隨機排程隱藏參數 | 全部 local windows、每窗／每日／總次數、最小間隔與活動上限各自顯示。 |
| R06 簽署前提隱藏 | 缺少私鑰與 blinding review 納入同一 issue list；編輯後確認失效。 |
| R07 工作無保存保護 | `.partdraft` 無私鑰工作檔、未完成數值還原、金鑰與研究變更分別追蹤、離頁與取代確認。 |
| R08 範例覆寫研究規則 | 先產生變更預覽與驗證，再明確套用；提供帶版本檢查的復原。 |
| R09 錯誤無法定位 | Study 子區塊映射、完整欄位 path、展開折疊容器並聚焦錯誤控制；未知 path 有界停止。 |
| R10 表單過長 | 五個研究設定區塊；總覽、參與者內容與模擬器獨立置於簽署前。 |
| R11 預覽與模擬不足 | 完整既有參與者內容、情境模板、registry 欄位事件編輯器、明確時鐘基準、具體錯誤及結果失效。 |
| R12 回傳資料計數誤解 | 系統／收集器事件分開；使用 commit 起訖與 durable head 判斷切片。 |
| R13 外部工具交接缺漏 | 部署所需 digest／key ID、個人化 CLI、接收端與 inventory/materialize 分析指引。 |

另外提供最低 App versionCode 欄位、事件登錄不相容診斷、32 MiB 大小預檢，
以及原生 WebMCP 五個工具與專屬 24 小時研究總覽。

## 改進驗證

- 單元／契約測試：25 個測試檔、322 項通過，包含時間軸、所有編輯型別、草稿、WebMCP、模擬輸入、密文拒絕與 commit 摘要。
- 原生 Codex 瀏覽器：發現五個 WebMCP 工具；直接設定 09:00–17:00 加速度收集並確認表單、時間軸同步。
- 既有五天範本：全部六題與三個 occurrence 可見，第三天 12:00–17:00 為 500 kbps、17:00 顯示六題問卷。
- 匯入與編輯只作用於本機測試草稿；未發布網站或變更既有裝置研究。

- Svelte／TypeScript：零錯誤、零警告；正式靜態建置成功。研究頁 JS 約 644 kB（gzip 約 159 kB），仍有建置器的 chunk-size 提示。
- 端到端流程：建立研究、設定檔新增／移除、簽署、四檔下載、canonical roundtrip，Kotlin `check-config` 驗證成功。
- 改進端到端流程：匯入金鑰保存、六題問卷、總覽回到活動規則、含小數的未完成草稿還原、相符私鑰、簽署確認、390px 英／中文排版、模擬結果失效與真實 conformance 密文讀取通過。
- 改進測試會在本機 `output/playwright/researcher-improvements/` 產生結果 JSON、桌面與手機畫面；產物不納入版本控制。
- 尚未進行目標研究者訪談或 Android 實機試驗；研究設計預覽不能替代實際通知、權限、網路及背景執行試跑。

### 重現驗證

從 repository 根目錄執行以下命令。需先具備專案要求的 Java、Node.js 與 pnpm 環境；瀏覽器測試使用編譯後的研究工具 CLI。

```sh
./gradlew :researcher-tools:installDist
pnpm --dir web install --frozen-lockfile
pnpm --dir web exec playwright install chromium
pnpm --dir web run check
pnpm --dir web run test
pnpm --dir web run build
pnpm --dir web run preview --host 127.0.0.1 --port 4173
```

保持預覽伺服器運行，在另一個終端從 repository 根目錄執行：

```sh
ORIGIN=http://127.0.0.1:4173 pnpm --dir web run e2e
ORIGIN=http://127.0.0.1:4173 pnpm --dir web run e2e:improvements
```

WebMCP 的原生工具發現需另外在支援該 API 的瀏覽器與助理中驗證，操作方式與功能範圍見 [WebMCP 文件](researcher-webmcp.md)。
