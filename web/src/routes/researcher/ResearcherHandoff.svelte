<script lang="ts">
  import Disclosure from '$lib/ui/Disclosure.svelte';
  import { configurationDigest } from '$lib/particeps/bundle';
  import type { Draft } from './draft.svelte';
  let { draft, locale = 'en' }: { draft: Draft; locale?: 'en' | 'zh-TW' } = $props();
  const zh = $derived(locale === 'zh-TW');
  const digest = $derived(Array.from(configurationDigest(draft.document), byte => byte.toString(16).padStart(2, '0')).join(''));
  const repo = 'https://github.com/JacobLinCool/particeps/blob/main';
</script>
<Disclosure label={zh ? '部署、個別發送與分析交接' : 'Deployment, personalization and analysis handoff'} icon="send">
  <div class="handoff">
    <p>{zh ? '簽署前先以試用裝置驗證收集、問卷、傳送及解密。下列識別值會隨研究修改而變動；部署請使用最後簽署版本。' : 'Pilot collection, surveys, delivery and decryption on a test device before distribution. These identifiers change with edits; deploy using the final signed configuration.'}</p>
    <dl><dt>ALLOWED_CONFIGURATION_SHA256</dt><dd><code>{digest}</code></dd><dt>ALLOWED_RESEARCHER_KEY_ID</dt><dd><code>{draft.exportKeyId}</code></dd></dl>
    <p>{zh ? '接收端每個部署固定一個設定 digest 與金鑰 ID。請設定 HTTPS endpoint、UPLOAD_PATH、私有 R2 bucket 與 hostname；它只接收密文，下載與分析由研究人員端完成。個人化設定的 digest 各不相同，須各自配置接收端。' : 'Each receiver deployment accepts one configuration digest and key ID. Configure the HTTPS endpoint, UPLOAD_PATH, private R2 bucket and hostname. It accepts ciphertext; researchers retrieve and analyze it separately. Personalized configurations have different digests and require matching receiver deployments.'} <a href={`${repo}/receiver/README.md`} target="_blank" rel="noreferrer">{zh ? '接收端部署指南' : 'Receiver deployment guide'}</a></p>
    <p>{zh ? '批次發送：準備無標題 TSV，每列依序為 configuration_id、assigned_participant_id，以 Tab 分隔；識別碼與姓名的對照表另行保管。使用 canonical 設定與相符的簽署私鑰產生每人的檔案。' : 'Batch distribution: prepare a headerless TSV with configuration_id then assigned_participant_id, separated by a tab. Keep the identity roster separately. Personalize the canonical configuration using its matching signing private key.'}</p>
    <pre><code>researcher-tools personalize --config study.json --mapping assignments.tsv --private signing.key --key-id SIGNER_KEY_ID --output-dir personalized</code></pre>
    <p>{zh ? '將 SIGNER_KEY_ID 替換為：' : 'Replace SIGNER_KEY_ID with:'} <code>{draft.signerKeyId}</code> · <a href={`${repo}/README.md#researcher-tools`} target="_blank" rel="noreferrer">{zh ? '安裝研究工具' : 'Researcher tools setup'}</a></p>
    <p>{zh ? '完整分析：在 particeps-analysis 目錄執行下列流程。先收齊手動匯出及接收端密文，再建立完整 commit chain 的 Parquet。keys.json 依指南建立於本機，只供離線解密使用。' : 'Full analysis: run the following from particeps-analysis. Inventory manual exports and receiver ciphertext, then materialize complete commit chains into Parquet. Create keys.json locally as described in the guide for offline decryption.'}</p>
    <pre><code>uv run particeps-analysis inventory --workspace /secure/work --local /path/to/exports
uv run particeps-analysis materialize --workspace /secure/work --keys /secure/keys.json --output /secure/new-dataset</code></pre>
    <a href={`${repo}/particeps-analysis/README.md`} target="_blank" rel="noreferrer">{zh ? '密鑰檔格式、R2 讀取與分析指南' : 'Key file format, R2 retrieval and analysis guide'}</a>
  </div>
</Disclosure>
<style>
  .handoff { display: grid; gap: var(--sp-5); padding-block: var(--sp-5); }
  dl { margin: 0; } dd { margin: var(--sp-2) 0 var(--sp-4); overflow-wrap: anywhere; }
  pre { white-space: pre-wrap; overflow-wrap: anywhere; font-size: var(--type-fine); padding: var(--sp-4); background: var(--surface-sunk); }
</style>
