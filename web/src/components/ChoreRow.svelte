<script lang="ts">
  import DueBadge from './DueBadge.svelte';
  import Icon from './Icon.svelte';
  import type { Chore } from '../lib/api';
  import { dayWord } from '../lib/chores';
  import { tIn, type Lang } from '../lib/i18n';
  import { longpress } from '../lib/longpress';

  let { chore, today, lang, ondone, onlong }: { chore: Chore; today: string; lang: Lang; ondone: () => void; onlong: () => void } = $props();

  const who = $derived(
    chore.last ? tIn(lang, 'list.who_when', { who: chore.last.by.display_name, when: dayWord(chore.last.done_on, today, lang) }) : tIn(lang, 'list.never_done'),
  );
  const doneToday = $derived(chore.days_since === 0);
</script>

<li class="row" use:longpress={onlong}>
  <a class="main" href="/chores/{chore.id}" draggable="false">
    <span class="name" data-nowrap>{chore.name}</span>
    <span class="who" data-nowrap>{who}</span>
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

  .name,
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
</style>
