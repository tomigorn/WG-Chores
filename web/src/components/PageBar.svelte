<script lang="ts">
  import type { Snippet } from 'svelte';
  import Icon from './Icon.svelte';
  import { t } from '../lib/i18n';

  let { title, back = null, left, right }: { title: string; back?: string | null; left?: Snippet; right?: Snippet } = $props();
</script>

<header class="bar">
  <div class="side">
    {#if left}
      {@render left()}
    {:else if back}
      <a class="icon-btn" href={back} aria-label={t('chore.back')}><Icon name="arrow-left" size={22} /></a>
    {/if}
  </div>
  <h1 class="title clamp" data-clamp="2">{title}</h1>
  <div class="side end">
    {#if right}{@render right()}{/if}
  </div>
</header>

<style>
  .bar {
    container-type: inline-size;
    position: sticky;
    top: env(safe-area-inset-top, 0);
    z-index: 10;
    display: grid;
    grid-template-columns: auto minmax(0, 1fr) auto;
    align-items: center;
    gap: var(--s1);
    min-height: var(--bar-h);
    padding: 0 var(--s2);
    background: var(--bg);
    border-bottom: 1px solid var(--border);
  }

  .side {
    display: flex;
    align-items: center;
    min-width: var(--tap);
  }

  .end {
    justify-content: flex-end;
  }

  .end:empty {
    min-width: 0;
  }

  .title {
    padding: 0 var(--s1);
    font-size: var(--fs-lg);
    font-weight: 680;
    letter-spacing: -0.01em;
    line-height: 1.25;
  }

  @container (max-width: 21em) {
    .title {
      font-size: var(--fs-md);
    }
  }

  .bar :global(.icon-btn) {
    display: grid;
    place-items: center;
    width: var(--tap);
    height: var(--tap);
    border-radius: 50%;
    color: var(--text);
    text-decoration: none;
  }

  .bar :global(.icon-btn:hover) {
    background: var(--surface);
  }

  .bar :global(.text-btn) {
    display: inline-flex;
    align-items: center;
    gap: var(--s1);
    min-height: var(--tap);
    padding: 0 var(--s3);
    border: 0;
    border-radius: var(--radius);
    background: none;
    color: var(--accent);
    font-weight: 600;
    text-decoration: none;
    white-space: nowrap;
    cursor: pointer;
  }

  .bar :global(.text-btn:hover) {
    background: var(--surface);
  }

  .bar :global(.text-btn:disabled) {
    opacity: 0.5;
    cursor: default;
  }
</style>
