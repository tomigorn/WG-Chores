<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import { t } from '../lib/i18n';
  import { PLAY_STORE_URL, canPromptInstall, touchPoints, dismissBanner, dismissedAt, installBannerMode, isStandalone, onInstallPrompt, promptInstall } from '../lib/install';

  let { ua = navigator.userAgent, standalone = isStandalone(), touch = touchPoints(), inset = false }: { ua?: string; standalone?: boolean; touch?: number; inset?: boolean } = $props();

  let dismissed = $state(dismissedAt());
  let prompt = $state(canPromptInstall());
  let guide = $state(false);

  const mode = $derived(installBannerMode(ua, standalone, dismissed, Date.now(), touch));
  // Android and desktop only offer what the browser can really install
  const visible = $derived(mode === 'ios-safari' || mode === 'ios-other' || ((mode === 'android' || mode === 'desktop') && prompt));
  const text = $derived(mode === 'ios-safari' ? t('install.ios') : mode === 'ios-other' ? t('install.ios_other') : t(`install.${mode === 'desktop' ? 'desktop' : 'android'}`));

  onMount(() => onInstallPrompt(() => (prompt = canPromptInstall())));

  function dismiss() {
    dismissBanner();
    dismissed = dismissedAt() ?? Date.now();
  }

  async function install() {
    await promptInstall().catch(() => false);
    prompt = canPromptInstall();
  }
</script>

{#if visible}
  <aside class="banner card" class:inset aria-label={t('install.guide_title')}>
    <span class="lead"><Icon name={mode === 'desktop' ? 'download' : mode === 'ios-other' ? 'compass' : 'smartphone'} size={20} /></span>
    <div class="body">
      <p data-copy>{text}</p>
      {#if mode !== 'ios-other'}
        <div class="actions">
          {#if mode === 'ios-safari'}
            <button class="btn btn-sm btn-primary" type="button" onclick={() => (guide = true)}>{t('install.how')}</button>
          {:else}
            <button class="btn btn-sm btn-primary" type="button" onclick={install}>{t('install.install')}</button>
            {#if mode === 'android' && PLAY_STORE_URL}<a class="btn btn-sm" href={PLAY_STORE_URL} rel="noopener">{t('install.play')}</a>{/if}
          {/if}
        </div>
      {/if}
    </div>
    <button class="close" type="button" aria-label={t('install.dismiss')} onclick={dismiss}><Icon name="x" size={18} /></button>
  </aside>
{/if}

<Sheet open={guide} onclose={() => (guide = false)} label={t('install.guide_title')}>
  <div class="guide">
    <h2 data-copy>{t('install.guide_title')}</h2>
    <ol>
      <li><span class="step"><Icon name="share" size={22} /></span><span data-copy>{t('install.step_share')}</span></li>
      <li><span class="step"><Icon name="square-plus" size={22} /></span><span data-copy>{t('install.step_add')}</span></li>
      <li><span class="step"><Icon name="check" size={22} /></span><span data-copy>{t('install.step_confirm')}</span></li>
    </ol>
    <button class="btn btn-primary btn-block" type="button" onclick={() => (guide = false)}>{t('install.done')}</button>
  </div>
</Sheet>

<style>
  .banner {
    display: flex;
    align-items: flex-start;
    gap: var(--s3);
    padding: var(--s3) var(--s2) var(--s3) var(--s4);
  }

  .inset {
    margin: var(--s3) var(--s4) 0;
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

  .body {
    display: flex;
    flex: 1;
    flex-direction: column;
    gap: var(--s2);
    min-width: 0;
    padding-top: var(--s1);
    text-wrap: pretty;
  }

  .actions {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s2);
  }

  .close {
    display: grid;
    flex: none;
    place-items: center;
    width: var(--tap);
    height: var(--tap);
    margin: calc(-1 * var(--s2)) 0;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--muted);
    cursor: pointer;
  }

  .close:hover {
    background: var(--bg);
  }

  /* narrow (small screen or large text): icon and close on top, the text gets the full width below */
  @container (max-width: 21em) {
    .banner {
      display: grid;
      grid-template-columns: auto 1fr auto;
      align-items: center;
      row-gap: var(--s2);
    }

    .close {
      grid-column: 3;
      grid-row: 1;
      margin: calc(-1 * var(--s1)) 0;
    }

    .body {
      grid-column: 1 / -1;
      grid-row: 2;
      padding: 0;
    }
  }

  .guide {
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  .guide h2 {
    font-size: var(--fs-md);
    font-weight: 650;
  }

  ol {
    display: flex;
    flex-direction: column;
    gap: var(--s3);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  li {
    display: flex;
    align-items: center;
    gap: var(--s3);
  }

  .step {
    display: grid;
    flex: none;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius);
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--accent);
  }
</style>
