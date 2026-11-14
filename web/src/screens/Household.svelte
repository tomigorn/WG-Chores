<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import ChoreRow from '../components/ChoreRow.svelte';
  import DoneSheet from '../components/DoneSheet.svelte';
  import Icon from '../components/Icon.svelte';
  import InstallBanner from '../components/InstallBanner.svelte';
  import Sheet from '../components/Sheet.svelte';
  import TopBar from '../components/TopBar.svelte';
  import { api, ApiError, OfflineError, type By, type Chore, type Household, type Me, type ServerConfig } from '../lib/api';
  import { applyCompletion, doneWhen, groupChores, rebase, todayIn, withPending } from '../lib/chores';
  import { newUuid } from '../lib/done';
  import { t, type Lang } from '../lib/i18n';
  import type { FlushResult } from '../lib/outbox';
  import { navigate } from '../lib/router';
  import { clearSnack, rememberChores, showSnack, toast } from '../lib/snack';
  import { startSync } from '../lib/sync';
  import type { ThemeSetting } from '../lib/theme';

  let {
    hid,
    config,
    me,
    lang,
    theme,
    ontheme,
    onlogout,
    onopened,
  }: {
    hid: number;
    config: ServerConfig;
    me: Me;
    lang: Lang;
    theme: ThemeSetting;
    ontheme: (s: ThemeSetting) => void;
    onlogout: () => void;
    onopened: (hid: number) => void;
  } = $props();

  interface Cached {
    household: Household;
    chores: Chore[];
  }

  let household: Household | null = $state(null);
  let households: Household[] = $state([]);
  let chores: Chore[] = $state([]);
  let loaded = $state(false);
  let offline = $state(false);
  let failed = $state(false);
  let pending = $state(0);
  let today = $state(untrack(() => todayIn(config.tz)));
  let switcherOpen = $state(false);
  let sheet: { chore: Chore; prev: Chore; uuid: string | null; initial: { doneOn: string; note: string | null } } | null = $state(null);
  let sheetOpen = $state(false);

  const store = untrack(() => startSync(me.id));
  const { outbox, done } = store;
  let fetchSeq = 0;
  let mutations = 0;

  const meBy: By = $derived({ id: me.id, display_name: me.display_name, former: false });
  const groups = $derived(groupChores(chores, lang === 'de' ? 'de-CH' : 'en-GB'));

  const show = showSnack;

  function flushed(r: FlushResult) {
    if (r.sent || r.dropped.length) load();
  }

  async function load() {
    const seq = ++fetchSeq;
    const mut = mutations;
    const stale = () => seq !== fetchSeq || mut !== mutations;
    try {
      const [h, list] = await Promise.all([api.household(hid), api.chores(hid)]);
      const queued = await outbox.items();
      if (stale()) return;
      today = todayIn(config.tz);
      household = h;
      rememberChores(list);
      chores = withPending(list, queued, meBy, today);
      offline = false;
      failed = false;
      if (!loaded && me.last_household_id !== hid) {
        api.setLastHousehold(hid).catch(() => {});
        onopened(hid);
      }
      loaded = true;
      store.setList(hid, { household: h, chores: list } satisfies Cached);
      if (queued.length) outbox.flush();
    } catch (e) {
      if (stale()) return;
      if (e instanceof OfflineError) {
        offline = true;
        if (loaded) return;
        const c = await store.getList<Cached>(hid);
        if (stale()) return;
        if (c) {
          today = todayIn(config.tz);
          household = c.household;
          rememberChores(c.chores);
          chores = withPending(rebase(c.chores, today), await outbox.items(), meBy, today);
          loaded = true;
        } else failed = true;
      } else if (e instanceof ApiError && (e.status === 404 || e.status === 403)) {
        navigate('/households', { replace: true });
      } else if (!loaded) failed = true;
    }
  }

  function setRow(c: Chore) {
    mutations++;
    chores = chores.map((x) => (x.id === c.id ? c : x));
  }

  function current(id: number): Chore | undefined {
    return chores.find((c) => c.id === id);
  }

  async function mark(c: Chore, doneOn: string | null, note: string | null) {
    today = todayIn(config.tz);
    doneOn ??= today;
    const prev = current(c.id) ?? c;
    const uuid = newUuid();
    setRow(applyCompletion(prev, { doneOn, by: meBy }, today));
    show({
      icon: 'check',
      strong: c.name,
      text: t('done.snack', { when: doneWhen(doneOn, today, lang) }),
      actions: [
        { label: t('done.undo'), run: () => undo(c.id, prev, uuid) },
        { label: t('done.other_date'), run: () => openSheet(c, prev, uuid, { doneOn, note }) },
      ],
    });
    const r = await done.mark(c.id, doneOn, note, uuid);
    if (r.rejected) setRow(prev);
  }

  async function undo(id: number, prev: Chore, uuid: string) {
    clearSnack();
    const now = current(id);
    setRow(prev);
    const r = await done.undo(uuid);
    if (r === 'ok') return load();
    if (now) setRow(now);
    toast(t(r === 'offline' ? 'done.connect' : 'done.failed'));
  }

  function openSheet(c: Chore, prev: Chore, uuid: string | null, initial: { doneOn: string; note: string | null }) {
    clearSnack();
    today = todayIn(config.tz);
    sheet = { chore: c, prev, uuid, initial };
    sheetOpen = true;
  }

  async function saveSheet(doneOn: string, note: string | null) {
    const s = sheet;
    sheetOpen = false;
    if (!s) return;
    if (s.uuid === null) return mark(s.chore, doneOn, note);
    const before = current(s.chore.id);
    setRow(applyCompletion(s.prev, { doneOn, by: meBy }, today));
    const r = await done.change(s.uuid, { doneOn, note });
    if (r === 'ok') {
      const uuid = s.uuid;
      show({
        icon: 'check',
        strong: s.chore.name,
        text: t('done.snack', { when: doneWhen(doneOn, today, lang) }),
        actions: [{ label: t('done.undo'), run: () => undo(s.chore.id, s.prev, uuid) }],
      });
      load();
    } else {
      if (before) setRow(before);
      toast(t(r === 'offline' ? 'done.connect' : 'done.failed'));
    }
  }

  async function openSwitcher() {
    switcherOpen = true;
    try {
      households = await api.households();
    } catch {
      if (!households.length && household) households = [household];
    }
  }

  function go(path: string) {
    switcherOpen = false;
    navigate(path);
  }

  onMount(() => {
    const offCount = outbox.onChange((n) => (pending = n));
    const offFlushed = outbox.onFlushed(flushed);
    outbox.items().then((i) => (pending = i.length));
    load();
    let timer: ReturnType<typeof setInterval> | null = null;
    const tick = () => {
      if (timer) clearInterval(timer);
      timer = document.visibilityState === 'visible' ? setInterval(load, 60_000) : null;
    };
    const visible = () => {
      tick();
      if (document.visibilityState === 'visible') load();
    };
    const online = () => load();
    const wentOffline = () => (offline = true);
    document.addEventListener('visibilitychange', visible);
    addEventListener('online', online);
    addEventListener('offline', wentOffline);
    tick();
    return () => {
      offCount();
      offFlushed();
      if (timer) clearInterval(timer);
      document.removeEventListener('visibilitychange', visible);
      removeEventListener('online', online);
      removeEventListener('offline', wentOffline);
    };
  });
</script>

<TopBar title={household?.name ?? ''} householdId={hid} {me} {pending} {offline} {theme} onswitch={openSwitcher} onstatus={toast} {ontheme} onnavigate={(p) => navigate(p)} {onlogout} />

<main class="page">
  <InstallBanner inset />
  {#if !loaded && failed}
    <div class="state">
      <Icon name={offline ? 'wifi-off' : 'circle-alert'} size={28} />
      <p>{t(offline ? 'error.offline' : 'error.generic')}</p>
      <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
    </div>
  {:else if !loaded}
    <div class="skeleton" aria-busy="true" aria-label={t('list.loading')}>
      {#each [0, 1, 2, 3, 4, 5] as i (i)}<div class="sk-row"><span></span><span></span></div>{/each}
    </div>
  {:else if chores.length === 0}
    <div class="state">
      <Icon name="list-checks" size={32} />
      <h1 class="state-title">{t('list.empty_title')}</h1>
      <p>{t('list.empty_text')}</p>
      <a class="btn btn-primary" href="/households/{hid}/new-chore"><Icon name="plus" size={18} />{t('list.add_chore')}</a>
    </div>
  {:else}
    {#if offline}<p class="note">{t('list.offline_note')}</p>{/if}
    {#each groups as g (g.category ?? '')}
      <section class="group">
        <h2 class="cat" data-nowrap>{g.category ?? t('list.other_category')}</h2>
        <ul class="list">
          {#each g.chores as c (c.id)}
            <ChoreRow chore={c} {today} {lang} ondone={() => mark(c, null, null)} onlong={() => openSheet(c, c, null, { doneOn: todayIn(config.tz), note: null })} />
          {/each}
        </ul>
      </section>
    {/each}
    <div class="end">
      <a class="btn" href="/households/{hid}/new-chore"><Icon name="plus" size={18} />{t('list.add_chore')}</a>
    </div>
  {/if}
</main>

{#if sheet}
  <DoneSheet open={sheetOpen} name={sheet.chore.name} {today} {lang} initial={sheet.initial} onsave={saveSheet} onclose={() => (sheetOpen = false)} />
{/if}

<Sheet open={switcherOpen} onclose={() => (switcherOpen = false)} label={t('menu.switch')}>
  <h2 class="sheet-title">{t('menu.your_households')}</h2>
  <ul class="hh-list">
    {#each households as h (h.id)}
      <li>
        <button class="hh-item" type="button" aria-current={h.id === hid ? 'page' : undefined} onclick={() => go(`/households/${h.id}`)}>
          <Icon name="house" size={18} />
          <span class="hh-name" data-nowrap>{h.name}</span>
          {#if h.id === hid}<Icon name="check" size={18} />{/if}
        </button>
      </li>
    {/each}
  </ul>
  <div class="hh-actions">
    <button class="btn" type="button" onclick={() => go(`/households/${hid}/settings`)}><Icon name="users" size={18} />{t('menu.household_settings')}</button>
    <button class="btn" type="button" onclick={() => go('/households?new=1')}><Icon name="plus" size={18} />{t('menu.new_household')}</button>
  </div>
</Sheet>

<style>
  .page {
    max-width: 640px;
    margin: 0 auto;
    padding-bottom: calc(64px + 2rem + env(safe-area-inset-bottom));
  }

  .group {
    margin-top: var(--s2);
  }

  .cat {
    position: sticky;
    top: calc(var(--bar-h) + env(safe-area-inset-top, 0px));
    z-index: 5;
    padding: var(--s4) var(--s4) var(--s2);
    background: var(--bg);
    font-size: var(--fs-sm);
    line-height: 20px;
    font-weight: 650;
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .list {
    margin: 0;
    padding: 0;
    list-style: none;
    border-block: 1px solid var(--border);
    background: var(--surface);
  }

  .note {
    padding: var(--s3) var(--s4) 0;
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .end {
    display: flex;
    justify-content: center;
    padding: var(--s6) var(--s4);
  }

  .state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s3);
    padding: var(--s12) var(--s6);
    text-align: center;
    text-wrap: balance;
    color: var(--muted);
  }

  .state-title {
    font-size: var(--fs-lg);
    color: var(--text);
  }

  .state .btn {
    margin-top: var(--s2);
  }

  .skeleton {
    margin-top: var(--s10);
    border-block: 1px solid var(--border);
    background: var(--surface);
  }

  .sk-row {
    display: flex;
    flex-direction: column;
    justify-content: center;
    gap: var(--s2);
    height: 64px;
    padding: 0 var(--s4);
  }

  .sk-row + .sk-row {
    border-top: 1px solid var(--border);
  }

  .sk-row span {
    height: 10px;
    width: 45%;
    border-radius: 5px;
    background: var(--border);
  }

  .sk-row span + span {
    width: 28%;
    opacity: 0.6;
  }

  .sheet-title {
    font-size: var(--fs-md);
    font-weight: 650;
    margin-bottom: var(--s2);
  }

  .hh-list {
    margin: 0 0 var(--s4);
    padding: 0;
    list-style: none;
  }

  .hh-item {
    display: flex;
    align-items: center;
    gap: var(--s3);
    width: 100%;
    min-height: 52px;
    padding: 0 var(--s2);
    border: 0;
    border-radius: var(--radius);
    background: none;
    text-align: left;
    cursor: pointer;
  }

  .hh-item:hover {
    background: var(--bg);
  }

  .hh-item :global(svg) {
    color: var(--muted);
  }

  .hh-item[aria-current] {
    font-weight: 600;
  }

  .hh-item[aria-current] :global(svg:last-child) {
    color: var(--accent);
  }

  .hh-actions {
    display: grid;
    grid-template-columns: minmax(0, 1fr);
    gap: var(--s2);
  }

  .hh-name {
    flex: 1;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  @media (min-width: 640px) {
    .list,
    .skeleton {
      border: 1px solid var(--border);
      border-radius: 12px;
      overflow: hidden;
    }

    .group {
      padding: 0 var(--s4);
    }

    .cat {
      padding-inline: var(--s1);
    }
  }
</style>
