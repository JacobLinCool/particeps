<script lang="ts">
  import type { WebMcpStatus } from '$lib/particeps/webmcp';
  let { status, locale = 'en' }: { status: WebMcpStatus; locale?: 'en' | 'zh-TW' } = $props();
  const copy = $derived(locale === 'zh-TW' ? {
    title: 'AI 協作', ready: '已可透過 WebMCP 編輯研究', registering: '正在連接研究編輯工具…',
    unsupported: '此瀏覽器尚未提供 WebMCP', failed: 'WebMCP 工具註冊失敗', stopped: 'AI 協作已停止',
    readyDetail: '可請支援的 AI 助理設定資料來源、採樣時段與問卷。變更會同步顯示於本頁，供你檢查。',
    unsupportedDetail: '你仍可使用所有研究表單。AI 直接編輯需要瀏覽器與助理共同支援 WebMCP。',
    failedDetail: '重新開啟此頁以重試；也可繼續使用研究表單。'
  } : {
    title: 'AI collaboration', ready: 'Study editing is available through WebMCP', registering: 'Connecting study authoring tools…',
    unsupported: 'This browser does not provide WebMCP yet', failed: 'WebMCP tool registration failed', stopped: 'AI collaboration stopped',
    readyDetail: 'Ask a supported assistant to configure collectors, collection windows and surveys. Changes appear in this page for your review.',
    unsupportedDetail: 'All study forms remain available. Direct AI editing requires WebMCP support in both the browser and assistant.',
    failedDetail: 'Reopen this page to retry, or continue using the study forms.'
  });
</script>

<aside class="webmcp-status" data-state={status} aria-label={copy.title}>
  <p class="status-line" role="status"><span class="status-dot" aria-hidden="true"></span>{copy[status]}</p>
  {#if status === 'ready' || status === 'unsupported' || status === 'failed'}
    <p class="status-detail">{status === 'ready' ? copy.readyDetail : status === 'failed' ? copy.failedDetail : copy.unsupportedDetail}</p>
  {/if}
</aside>

<style>
  .webmcp-status { padding: .85rem 1rem; border: 1px solid var(--line, #d9ddd8); border-radius: .7rem; font-size: .85rem; }
  .status-line { display: flex; align-items: center; gap: .5rem; margin: 0; font-weight: 600; }
  .status-dot { flex: 0 0 .45rem; width: .45rem; height: .45rem; border-radius: 50%; background: #7c827d; }
  [data-state='ready'] .status-dot { background: #387658; }
  .status-detail { margin: .4rem 0 0 .95rem; line-height: 1.5; opacity: .8; }
</style>
