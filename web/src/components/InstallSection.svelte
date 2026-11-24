<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './Icon.svelte';
  import InstallGuide from './InstallGuide.svelte';
  import { t } from '../lib/i18n';
  import { canPromptInstall, installMode, isStandalone, onInstallPrompt, promptInstall, touchPoints } from '../lib/install';

  let { ua = navigator.userAgent, standalone = isStandalone(), touch = touchPoints() }: { ua?: string; standalone?: boolean; touch?: number } = $props();

  let prompt = $state(canPromptInstall());
  let installed = $state(false);
  let guide = $state(false);

  const mode = $derived(installed ? 'none' : installMode(ua, standalone, touch));

  onMount(() => onInstallPrompt(() => (prompt = canPromptInstall())));

  async function install() {
    installed = await promptInstall().catch(() => false);
    prompt = canPromptInstall();
  }
</script>

<section class="section">
  <h2 data-copy class="section-title">{t('settings.app')}</h2>
  <div class="card app">
    {#if mode === 'none'}
      <p data-copy class="line"><span class="lead"><Icon name="check" size={20} /></span><span>{t('settings.installed')}</span></p>
    {:else}
      <p data-copy class="line">
        <span class="lead"><Icon name={mode === 'desktop' ? 'download' : mode === 'ios-other' ? 'compass' : 'smartphone'} size={20} /></span>
        <span>{mode === 'ios-other' ? t('install.ios_other') : t('settings.install_hint')}</span>
      </p>
      {#if mode === 'ios-safari'}
        <button class="btn btn-primary" type="button" onclick={() => (guide = true)}>{t('install.how')}</button>
      {:else if (mode === 'android' || mode === 'desktop') && prompt}
        <button class="btn btn-primary" type="button" onclick={install}>{t('install.install')}</button>
      {:else if mode === 'android'}
        <p data-copy class="how">{t('settings.android_how')}</p>
        <p data-copy class="how">{t('settings.android_fix')}</p>
      {:else if mode === 'desktop'}
        <p data-copy class="how">{t('settings.desktop_how')}</p>
      {/if}
    {/if}
  </div>
</section>

<InstallGuide open={guide} onclose={() => (guide = false)} />

<style>
  .app {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s3);
    padding: var(--s3) var(--s4);
  }

  .line {
    display: flex;
    align-items: center;
    gap: var(--s3);
    margin: 0;
    text-wrap: pretty;
  }

  .lead {
    display: grid;
    flex: none;
    place-items: center;
    width: 36px;
    height: 36px;
    border-radius: var(--radius);
    border: 1px solid var(--border);
    color: var(--accent);
  }

  .how {
    margin: 0;
    font-size: var(--fs-sm);
    color: var(--muted);
    text-wrap: pretty;
  }
</style>
