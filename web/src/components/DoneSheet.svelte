<script lang="ts">
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import { addDays, dayWord } from '../lib/chores';
  import { t, type Lang } from '../lib/i18n';

  let {
    open,
    name,
    today,
    lang,
    initial,
    onsave,
    onclose,
    ondelete,
  }: {
    open: boolean;
    name: string;
    today: string;
    lang: Lang;
    initial: { doneOn: string; note: string | null };
    onsave: (doneOn: string, note: string | null) => void;
    onclose: () => void;
    ondelete?: () => void;
  } = $props();

  type Choice = 0 | 1 | 2 | 'pick';
  let choice = $state<Choice>(0);
  let picked = $state('');
  let note = $state('');
  let dateInput: HTMLInputElement | undefined = $state();

  $effect(() => {
    if (!open) return;
    const back = [0, 1, 2].find((n) => addDays(today, -n) === initial.doneOn) as 0 | 1 | 2 | undefined;
    choice = back ?? 'pick';
    picked = initial.doneOn;
    note = initial.note ?? '';
  });

  const minDate = $derived(addDays(today, -5 * 365));
  const doneOn = $derived(choice === 'pick' ? picked : addDays(today, -choice));
  const valid = $derived(/^\d{4}-\d{2}-\d{2}$/.test(doneOn) && doneOn <= today && doneOn >= minDate);

  function pick() {
    choice = 'pick';
    if (!picked || picked > today) picked = today;
    queueMicrotask(() => {
      dateInput?.focus();
      try {
        dateInput?.showPicker();
      } catch {
        /* not supported or not allowed */
      }
    });
  }

  function submit(e: SubmitEvent) {
    e.preventDefault();
    if (valid) onsave(doneOn, note.trim() || null);
  }

  const days = [
    { n: 0, key: 'sheet.today' },
    { n: 1, key: 'sheet.yesterday' },
    { n: 2, key: 'sheet.two_days' },
  ] as const;
</script>

<Sheet {open} {onclose} label={t('sheet.title', { name })}>
  <form onsubmit={submit}>
    <h2 class="title"><span class="name" data-nowrap>{name}</span><span class="rest" data-nowrap>{t('sheet.done_on')}</span></h2>
    <div class="chips" role="radiogroup" aria-label={t('sheet.date')}>
      {#each days as d (d.n)}
        <button type="button" class="chip" role="radio" aria-checked={choice === d.n} onclick={() => (choice = d.n)} data-nowrap>{t(d.key)}</button>
      {/each}
      <button type="button" class="chip" role="radio" aria-checked={choice === 'pick'} onclick={pick} data-nowrap>
        <Icon name="calendar" size={16} />
        {choice === 'pick' && picked ? dayWord(picked, today, lang) : t('sheet.pick')}
      </button>
    </div>
    {#if choice === 'pick'}
      <div class="field">
        <label for="done-date">{t('sheet.date')}</label>
        <input id="done-date" class="input" type="date" max={today} min={minDate} required bind:value={picked} bind:this={dateInput} />
      </div>
    {/if}
    <div class="field">
      <label for="done-note">{t('sheet.note')}</label>
      <input id="done-note" class="input" maxlength="500" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder={t('sheet.note_placeholder')} bind:value={note} />
    </div>
    <button class="btn btn-primary btn-block" type="submit" disabled={!valid}>{t('sheet.save')}</button>
    {#if ondelete}
      <button class="btn btn-danger btn-block" type="button" onclick={ondelete}><Icon name="trash-2" size={18} />{t('entry.delete')}</button>
    {/if}
  </form>
</Sheet>

<style>
  form {
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  .title {
    display: flex;
    flex-wrap: wrap;
    column-gap: 0.3em;
    font-size: var(--fs-md);
    font-weight: 650;
    min-width: 0;
  }

  .name {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
    max-width: 100%;
  }

  .rest {
    flex: none;
    white-space: nowrap;
    font-weight: 500;
    color: var(--muted);
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s2);
  }

  .chip {
    flex: 1 1 40%;
  }

  @media (min-width: 480px) {
    .chip {
      flex: none;
    }
  }

  .chip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: var(--s1);
    min-height: 40px;
    padding: 0 var(--s3);
    border: 1px solid var(--border-strong);
    border-radius: 999px;
    background: var(--surface);
    font-size: var(--fs-sm);
    font-weight: 500;
    white-space: nowrap;
    cursor: pointer;
    transition: background-color var(--motion) ease, border-color var(--motion) ease;
  }

  .chip[aria-checked='true'] {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-text);
  }
</style>
