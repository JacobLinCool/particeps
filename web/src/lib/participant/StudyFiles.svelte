<script lang="ts">
  import { base } from '$app/paths';
  import Icon from '$lib/ui/Icon.svelte';
  import Section from '$lib/ui/Section.svelte';
  import { STUDY_CONFIGURATION_PATH, STUDY_GUIDE_PATH } from './content';
  import { m } from './messages.svelte';
  import { fetchStudyFile } from './study-download';

  let downloading = $state(false);
  let downloadFailed = $state(false);

  async function download(event: MouseEvent, path: string, filename: string) {
    event.preventDefault();
    if (downloading) return;
    downloading = true;
    downloadFailed = false;
    try {
      const file = await fetchStudyFile(`${base}${path}`);
      const url = URL.createObjectURL(file);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
    } catch {
      downloadFailed = true;
    } finally {
      downloading = false;
    }
  }
</script>

<Section id="study-files" icon="document" title={m('study.title')} lead={m('study.team')} variant="raised">
  <p>{m('study.lead')}</p>
  <div class="study-files__downloads">
    <a
      class="btn btn--primary"
      href={`${base}${STUDY_CONFIGURATION_PATH}`}
      download="study.partcfg"
      data-testid="download-study-configuration"
      aria-disabled={downloading}
      onclick={(event) => download(event, STUDY_CONFIGURATION_PATH, 'study.partcfg')}
    >
      <Icon name="download" size={18} />
      <span>{m('study.configurationDownload')}</span>
    </a>
    <a
      class="btn btn--quiet"
      href={`${base}${STUDY_GUIDE_PATH}`}
      download="participant-start.zh-TW.pdf"
      data-testid="download-study-guide"
      aria-disabled={downloading}
      onclick={(event) => download(event, STUDY_GUIDE_PATH, 'participant-start.zh-TW.pdf')}
    >
      <Icon name="document" size={18} />
      <span>{m('study.guideDownload')}</span>
    </a>
  </div>
  {#if downloading}
    <p class="fine" role="status">{m('study.downloading')}</p>
  {/if}
  {#if downloadFailed}
    <p class="fine" role="alert">{m('study.downloadFailed')}</p>
  {/if}
  <div class="stack stack--tight">
    <p class="fine">{m('study.importInstructions')}</p>
    <p class="fine">{m('study.startInstructions')}</p>
    <p class="fine">{m('study.deliveryInstructions')}</p>
  </div>
</Section>

<style>
  .study-files__downloads {
    display: flex;
    flex-wrap: wrap;
    gap: var(--sp-3);
    margin-block: var(--sp-5);
  }

  .study-files__downloads a {
    white-space: normal;
    text-align: start;
    max-inline-size: 100%;
  }
</style>
