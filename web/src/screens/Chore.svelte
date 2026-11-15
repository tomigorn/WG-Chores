<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import DoneSheet from '../components/DoneSheet.svelte';
  import Icon from '../components/Icon.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, ApiError, OfflineError, type By, type Chore, type Completion, type Me, type ServerConfig } from '../lib/api';
  import { applyCompletion, dayWord, rebase, todayIn } from '../lib/chores';
  import { t, type Lang } from '../lib/i18n';
  import { marker } from '../lib/marking';
  import { marks, type FlushResult, type OutboxItem } from '../lib/outbox';
  import { navigate } from '../lib/router';
  import { clearSnack, rememberChores, toast } from '../lib/snack';
  import { startSync } from '../lib/sync';
  import { choreMeta, lastDone, usualText } from '../lib/words';

  let { id, config, me, lang }: { id: number; config: ServerConfig; me: Me; lang: Lang } = $props();

  interface Entry {
    id: string;
    done_on: string;
    note: string | null;
    by: By;
    waiting: boolean;
  }

  interface Cached {
    chore: Chore;
    items: Completion[];
    next: string | null;
  }

  const PAGE = 20;
  const store = untrack(() => startSync(me.id));
  const { outbox, done } = store;

  let chore = $state<Chore | null>(null);
  let items: Completion[] = $state([]);
  let next: string | null = $state(null);
  let queued: OutboxItem[] = $state([]);
  let phase: 'loading' | 'ready' | 'failed' | 'gone' = $state('loading');
  let offline = $state(false);
  let loadingMore = $state(false);
  let today = $state(untrack(() => todayIn(config.tz)));
  let sentinel: HTMLButtonElement | undefined = $state();
  let mutations = 0;
  let seq = 0;

  let sheet: { mode: 'new' } | { mode: 'entry'; entry: Entry } | null = $state(null);
  let sheetOpen = $state(false);
  let confirmDelete: Entry | null = $state(null);

  const meBy: By = $derived({ id: me.id, display_name: me.display_name, former: false });
  const entries: Entry[] = $derived([
    ...marks(queued)
      .filter((q) => q.choreId === id)
      .map((q) => ({ id: q.uuid, done_on: q.doneOn, note: q.note, by: meBy, waiting: true }))
      .sort((a, b) => (a.done_on < b.done_on ? 1 : -1)),
    ...items.filter((i) => !queued.some((q) => q.uuid === i.id)).map((i) => ({ ...i, waiting: false })),
  ]);
  const last = $derived(chore ? lastDone(chore, today, lang) : null);
  const usual = $derived(chore ? usualText(chore.usual_interval, lang) : null);
  const hid = $derived(chore?.household_id ?? me.last_household_id);
  const back = $derived(hid ? `/households/${hid}` : '/households');

  function withQueued(c: Chore, q: OutboxItem[]): Chore {
    return marks(q).filter((i) => i.choreId === c.id).reduce((acc, i) => applyCompletion(acc, { doneOn: i.doneOn, by: meBy }, today), c);
  }

  async function load() {
    const s = ++seq;
    const mut = mutations;
    const stale = () => s !== seq || mut !== mutations;
    try {
      const [c, page] = await Promise.all([api.chore(id), api.history(id, { limit: PAGE })]);
      const q = await outbox.items();
      if (stale()) return;
      today = todayIn(config.tz);
      rememberChores([c]);
      queued = q;
      chore = withQueued(c, q);
      items = page.items;
      next = page.next;
      offline = false;
      phase = 'ready';
      store.setCached(`chore:${id}`, { chore: c, items: page.items, next: page.next } satisfies Cached);
    } catch (e) {
      if (stale()) return;
      if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
        phase = 'gone';
        return;
      }
      if (!(e instanceof OfflineError)) {
        if (phase === 'loading') phase = 'failed';
        return;
      }
      offline = true;
      if (phase === 'ready') return;
      const c = await store.getCached<Cached>(`chore:${id}`);
      if (stale()) return;
      if (!c) {
        phase = 'failed';
        return;
      }
      today = todayIn(config.tz);
      queued = await outbox.items();
      chore = withQueued(rebase([c.chore], today)[0]!, queued);
      items = c.items;
      next = null;
      phase = 'ready';
    }
  }

  async function more() {
    if (!next || loadingMore) return;
    loadingMore = true;
    try {
      const page = await api.history(id, { before: next, limit: PAGE });
      items = [...items, ...page.items.filter((i) => !items.some((x) => x.id === i.id))];
      next = page.next;
    } catch (e) {
      if (e instanceof OfflineError) offline = true;
    } finally {
      loadingMore = false;
    }
  }

  function edited() {
    mutations++;
  }

  const m = marker({
    done,
    tz: untrack(() => config.tz),
    me: () => meBy,
    lang: () => lang,
    get: () => chore ?? undefined,
    set: (c) => {
      edited();
      chore = c;
    },
    reload: load,
    setToday: (d) => (today = d),
    pending: (p) => (queued = 'add' in p ? [...queued, p.add] : queued.filter((q) => q.uuid !== p.remove)),
    settled: async () => (queued = await outbox.items()),
  });

  async function mark(doneOn: string | null, note: string | null): Promise<void> {
    if (chore) await m.mark(chore, doneOn, note);
  }

  function openNew() {
    clearSnack();
    today = todayIn(config.tz);
    sheet = { mode: 'new' };
    sheetOpen = true;
  }

  function openEntry(e: Entry) {
    today = todayIn(config.tz);
    sheet = { mode: 'entry', entry: e };
    sheetOpen = true;
  }

  async function save(doneOn: string, note: string | null) {
    const s = sheet;
    sheetOpen = false;
    if (!s) return;
    if (s.mode === 'new') return mark(doneOn, note);
    edited();
    const r = await done.change(s.entry.id, { doneOn, note });
    if (r === 'ok') {
      toast(t('entry.saved'));
      queued = await outbox.items();
      return load();
    }
    toast(t(r === 'offline' ? 'done.connect' : 'done.failed'));
  }

  function askDelete() {
    if (sheet?.mode !== 'entry') return;
    confirmDelete = sheet.entry;
    sheetOpen = false;
  }

  async function remove() {
    const e = confirmDelete;
    confirmDelete = null;
    if (!e) return;
    edited();
    const r = await done.undo(e.id, id);
    queued = await outbox.items();
    if (r === 'error') return toast(t('done.failed'));
    toast(t('entry.deleted'));
    if (r === 'ok') return load();
  }

  async function restore() {
    edited();
    try {
      chore = await api.restoreChore(id);
      toast(t('hh.restored', { name: chore.name }));
    } catch (e) {
      toast(t(e instanceof OfflineError ? 'done.connect' : 'done.failed'));
    }
  }

  function who(by: By): string {
    return by.former ? t('chore.former', { name: by.display_name }) : by.display_name;
  }

  function day(d: string): string {
    const w = dayWord(d, today, lang);
    return w.charAt(0).toLocaleUpperCase() + w.slice(1);
  }

  $effect(() => {
    if (!sentinel || typeof IntersectionObserver === 'undefined') return;
    const io = new IntersectionObserver((es) => es.some((e) => e.isIntersecting) && more(), { rootMargin: '200px' });
    io.observe(sentinel);
    return () => io.disconnect();
  });

  onMount(() => {
    const offFlushed = outbox.onFlushed((r: FlushResult) => {
      if (r.sent || r.dropped.length) load();
    });
    load();
    const visible = () => document.visibilityState === 'visible' && load();
    const online = () => load();
    document.addEventListener('visibilitychange', visible);
    addEventListener('online', online);
    return () => {
      offFlushed();
      document.removeEventListener('visibilitychange', visible);
      removeEventListener('online', online);
    };
  });
</script>

<PageBar title={chore?.name ?? ''} {back}>
  {#snippet right()}
    {#if chore && !chore.archived && !offline}
      <a class="text-btn" href="/chores/{id}/edit" aria-label={t('chore.edit_label', { name: chore.name })}><Icon name="pencil" size={18} /><span class="edit-word">{t('chore.edit')}</span></a>
    {/if}
  {/snippet}
</PageBar>

<main class="page">
  {#if phase === 'loading'}
    <div class="skeleton" aria-busy="true" aria-label={t('common.loading')}><span></span><span></span><span></span></div>
  {:else if phase === 'gone'}
    <div class="state">
      <Icon name="circle-alert" size={28} />
      <p data-copy>{t('chore.not_found')}</p>
      <a class="btn" href="/">{t('invite.go_home')}</a>
    </div>
  {:else if phase === 'failed'}
    <div class="state">
      <Icon name={offline ? 'wifi-off' : 'circle-alert'} size={28} />
      <p data-copy>{t(offline ? 'error.offline' : 'error.generic')}</p>
      <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
    </div>
  {:else if chore && last}
    <p class="meta" data-nowrap>{choreMeta(chore, lang)}</p>
    {#if offline}<p data-copy class="note">{t('list.offline_note')}</p>{/if}
    {#if chore.archived}
      <div class="archived">
        <span>{t('chore.archived_note')}</span>
        <button class="btn btn-sm" type="button" onclick={restore}><Icon name="rotate-ccw" size={16} />{t('chore.restore')}</button>
      </div>
    {/if}

    <section class="last card">
      <div class="days due-{chore.due_level}">
        {#if chore.days_since === null}
          <span class="never" data-nowrap>{t('badge.never')}</span>
        {:else}
          <span class="num" data-nowrap>{chore.days_since}</span><span class="unit" data-nowrap>{t('badge.days', { n: '' }).trim()}</span>
        {/if}
      </div>
      <div class="lines">
        <p class="when">{last.when}</p>
        {#if last.by || last.overdue}
          <p class="by">
            {#if last.by}<span data-nowrap>{last.by}</span>{/if}{#if last.by && last.overdue}{' '}{/if}{#if last.overdue}<span data-nowrap>{#if last.by}<span aria-hidden="true">·{'\u00a0'}</span>{/if}<span class="overdue">{last.overdue}</span></span>{/if}
          </p>
        {/if}
      </div>
    </section>
    {#if usual}<p class="usual">{usual}</p>{/if}

    <h2 data-copy class="sec">{t('chore.history')}</h2>
    {#if entries.length === 0}
      <p data-copy class="empty">{t('chore.no_history')}</p>
    {:else}
      <ul class="history card">
        {#each entries as e (e.id)}
          <li>
            <button type="button" class="entry" aria-label={t('chore.entry_label', { date: day(e.done_on), who: who(e.by) }) + (e.note ? `, “${e.note}”` : '')} onclick={() => openEntry(e)}>
              <span class="d" data-nowrap>{day(e.done_on)}</span>
              <span class="w">
                <span class="name clamp" data-clamp="2">{who(e.by)}</span>
                {#if e.note}<span class="n">“{e.note}”</span>{/if}
              </span>
              {#if e.waiting}<span class="wait" title={t('chore.waiting')}><Icon name="hourglass" size={14} /><span class="visually-hidden">{t('chore.waiting')}</span></span>{/if}
            </button>
          </li>
        {/each}
      </ul>
      {#if next}
        <button class="more" type="button" bind:this={sentinel} onclick={more} disabled={loadingMore}>{t('chore.load_more')}</button>
      {/if}
      <p data-copy class="hint">{t('chore.tap_hint')}</p>
    {/if}
  {/if}
</main>

{#if chore && phase === 'ready' && !chore.archived}
  <footer class="foot">
    <button class="btn btn-primary" type="button" onclick={() => chore && m.tap(chore)}><Icon name="check" size={18} />{t('chore.done_today')}</button>
    <button class="btn" type="button" onclick={openNew}>{t('chore.other_date')}</button>
  </footer>
{/if}

{#if sheet && chore}
  <DoneSheet
    open={sheetOpen}
    name={chore.name}
    {today}
    {lang}
    initial={sheet.mode === 'entry' ? { doneOn: sheet.entry.done_on, note: sheet.entry.note } : { doneOn: today, note: null }}
    onsave={save}
    onclose={() => (sheetOpen = false)}
    ondelete={sheet.mode === 'entry' ? askDelete : undefined}
  />
{/if}

<ConfirmSheet
  open={confirmDelete !== null}
  title={t('entry.delete_title')}
  text={confirmDelete ? t('entry.delete_text', { who: who(confirmDelete.by), date: day(confirmDelete.done_on) }) : ''}
  confirm={t('entry.delete_confirm')}
  cancel={t('entry.keep')}
  danger
  onconfirm={remove}
  onclose={() => (confirmDelete = null)}
/>

<style>
  .page {
    container-type: inline-size;
    max-width: 640px;
    margin: 0 auto;
    padding: var(--s3) var(--s4) calc(var(--tap) + var(--s12) + env(safe-area-inset-bottom));
  }

  .meta {
    color: var(--muted);
    font-size: var(--fs-sm);
    margin-bottom: var(--s4);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .note {
    margin: calc(-1 * var(--s2)) 0 var(--s4);
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .archived {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--s3);
    margin-bottom: var(--s4);
    padding: var(--s2) var(--s2) var(--s2) var(--s4);
    border: 1px dashed var(--border-strong);
    border-radius: 12px;
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .last {
    display: flex;
    align-items: center;
    gap: var(--s4);
    padding: var(--s3);
  }

  .days {
    display: flex;
    flex: none;
    align-items: baseline;
    justify-content: center;
    gap: 2px;
    min-width: 72px;
    height: 56px;
    padding: 0 var(--s3);
    border-radius: 10px;
    line-height: 56px;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }

  .num {
    font-size: 1.75rem;
    font-weight: 700;
    letter-spacing: -0.02em;
  }

  .unit {
    font-size: var(--fs-md);
    font-weight: 600;
  }

  .never {
    font-size: var(--fs-md);
    font-weight: 700;
  }

  .lines {
    display: flex;
    flex-direction: column;
    gap: 2px;
    min-width: 0;
  }

  .when {
    font-weight: 600;
  }

  .by {
    display: flex;
    flex-wrap: wrap;
    column-gap: 0.3em;
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .by > span {
    max-width: 100%;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .overdue {
    color: var(--due-due-fg);
    font-weight: 600;
  }

  .usual {
    margin: var(--s2) var(--s1) 0;
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .sec {
    margin: var(--s6) var(--s1) var(--s2);
    font-size: var(--fs-sm);
    line-height: 20px;
    font-weight: 650;
    color: var(--muted);
  }

  .history {
    margin: 0;
    padding: 0;
    list-style: none;
    overflow: hidden;
  }

  .history li + li {
    border-top: 1px solid var(--border);
  }

  .entry {
    display: flex;
    align-items: baseline;
    gap: var(--s3);
    width: 100%;
    min-height: 48px;
    padding: var(--s3) var(--s4);
    border: 0;
    background: none;
    text-align: left;
    cursor: pointer;
  }

  .entry:hover {
    background: var(--bg);
  }

  .d {
    flex: none;
    width: 5.5em;
    color: var(--muted);
    font-size: var(--fs-sm);
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }

  .w {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }


  .n {
    font-size: var(--fs-sm);
    font-style: italic;
    color: var(--muted);
    overflow-wrap: anywhere;
  }

  /* narrow (small screen or large text): the day box above the lines, history dates above who and note */
  @container (max-width: 21em) {
    .last {
      flex-wrap: wrap;
    }

    .entry {
      flex-direction: column;
      gap: var(--s1);
    }

    .d {
      width: auto;
    }
  }

  .wait {
    flex: none;
    align-self: center;
    color: var(--muted);
  }

  .more {
    display: block;
    width: 100%;
    min-height: var(--tap);
    margin-top: var(--s2);
    border: 0;
    background: none;
    color: var(--accent);
    font-size: var(--fs-sm);
    font-weight: 600;
    cursor: pointer;
  }

  .hint,
  .empty {
    margin: var(--s3) var(--s1) 0;
    font-size: var(--fs-xs);
    color: var(--muted);
    text-align: center;
  }

  .empty {
    text-align: left;
    font-size: var(--fs-sm);
  }

  @media (max-width: 400px) {
    .edit-word {
      display: none;
    }
  }

  .foot {
    position: fixed;
    inset: auto 0 0;
    z-index: 15;
    display: flex;
    gap: var(--s2);
    padding: var(--s3) max(var(--s4), env(safe-area-inset-right)) calc(var(--s3) + env(safe-area-inset-bottom)) max(var(--s4), env(safe-area-inset-left));
    background: var(--surface);
    border-top: 1px solid var(--border);
  }

  @media (min-width: 640px) {
    .foot {
      padding-inline: calc((100% - 640px) / 2 + var(--s4));
    }
  }

  .foot .btn {
    flex: 1 1 auto;
    padding: 0 var(--s3);
    min-width: 0;
  }

  @media (max-width: 400px) {
    .foot .btn {
      font-size: var(--fs-sm);
      gap: var(--s1);
    }
  }

  @media (max-width: 359px) {
    .foot .btn :global(svg) {
      display: none;
    }
  }

  .state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s3);
    padding: var(--s12) var(--s6);
    text-align: center;
    color: var(--muted);
  }

  .skeleton {
    display: flex;
    flex-direction: column;
    gap: var(--s3);
    padding-top: var(--s2);
  }

  .skeleton span {
    height: 12px;
    width: 40%;
    border-radius: 6px;
    background: var(--border);
  }

  .skeleton span:nth-child(2) {
    height: 80px;
    width: 100%;
    border-radius: 12px;
    opacity: 0.6;
  }
</style>
