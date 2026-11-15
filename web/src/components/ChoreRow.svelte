<script lang="ts">
  import DueBadge from './DueBadge.svelte';
  import Icon from './Icon.svelte';
  import type { Chore } from '../lib/api';
  import { dayWord } from '../lib/chores';
  import { tIn, type Lang } from '../lib/i18n';
  import { longpress } from '../lib/longpress';

  let { chore, today, lang, ondone, onlong }: { chore: Chore; today: string; lang: Lang; ondone: () => void; onlong: () => void } = $props();

  // who and when wrap as two units on narrow rows, never inside the date
  const when = $derived(chore.last ? tIn(lang, 'list.who_when', { who: '', when: dayWord(chore.last.done_on, today, lang) }).trim() : '');
  const doneToday = $derived(chore.days_since === 0);
</script>

<li class="row" use:longpress={onlong}>
  <a class="main" href="/chores/{chore.id}" draggable="false">
    <span class="name clamp" data-clamp="2">{chore.name}</span>
    {#if chore.last}
      <span class="who"><span data-nowrap>{chore.last.by.display_name}</span> <span data-nowrap>{when}</span></span>
    {:else}
      <span class="who"><span data-nowrap>{tIn(lang, 'list.never_done')}</span></span>
    {/if}
  </a>
  <DueBadge {chore} {lang} />
  <button class="done" class:today={doneToday} type="button" aria-label={tIn(lang, 'list.mark_done', { name: chore.name })} onclick={ondone}>
    <Icon name="check" size={20} />
  </button>
</li>

<style>
  .row {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto auto;
    align-items: center;
    gap: var(--s3);
    min-height: 64px;
    padding: 0 var(--s2) 0 var(--s4);
    background: var(--surface);
    -webkit-touch-callout: none;
    -webkit-user-select: none;
    user-select: none;
    -webkit-tap-highlight-color: transparent;
  }

  .row + :global(.row) {
    border-top: 1px solid var(--border);
  }

  .main {
    display: flex;
    flex-direction: column;
    justify-content: center;
    min-width: 0;
    min-height: 64px;
    padding: var(--s2) 0;
    color: inherit;
    text-decoration: none;
    -webkit-touch-callout: none;
  }

  .who {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .name {
    font-weight: 560;
  }

  .who {
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .row :global(.badge) {
    min-width: 48px;
    justify-content: center;
  }

  .done {
    display: grid;
    place-items: center;
    width: var(--tap);
    height: var(--tap);
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--muted);
    cursor: pointer;
  }

  .done :global(svg) {
    box-sizing: content-box;
    padding: 5px;
    border: 1.5px solid var(--border-strong);
    border-radius: 50%;
    transition: background-color var(--motion) ease, color var(--motion) ease, border-color var(--motion) ease, transform var(--motion) ease;
  }

  .done:hover :global(svg) {
    border-color: var(--accent);
    color: var(--accent);
  }

  .done:active :global(svg) {
    transform: scale(0.9);
  }

  .done.today :global(svg) {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-text);
  }

  /* narrow list (small screen or large text): the name may take two lines next to badge and tick, who · when gets the full width below */
  @container (max-width: 21em) {
    .row {
      grid-template-rows: auto auto;
      row-gap: 0;
      column-gap: var(--s2);
      padding-block: var(--s1);
    }

    .main {
      display: grid;
      grid-column: 1 / -1;
      grid-row: 1 / span 2;
      grid-template-columns: subgrid;
      grid-template-rows: subgrid;
      align-items: center;
      min-height: 0;
      padding: var(--s1) 0;
    }

    .name {
      grid-column: 1;
      grid-row: 1;
    }

    .who {
      display: flex;
      flex-wrap: wrap;
      column-gap: 0.3em;
      grid-column: 1 / -1;
      grid-row: 2;
      white-space: normal;
    }

    .who > span {
      max-width: 100%;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .row :global(.badge) {
      grid-column: 2;
      grid-row: 1;
      pointer-events: none;
    }

    .done {
      grid-column: 3;
      grid-row: 1;
    }
  }

  /* very narrow (a small phone at large text): name, then who · when, then badge and tick, each on the full width */
  @container (max-width: 16em) {
    .row {
      grid-template-rows: auto auto auto;
    }

    .main {
      grid-row: 1 / span 3;
    }

    .name {
      grid-column: 1 / -1;
    }

    .row :global(.badge) {
      grid-row: 3;
    }

    .done {
      grid-row: 3;
    }
  }
</style>
