<script lang="ts">
  import { onMount } from 'svelte';
  import Avatar from '../components/Avatar.svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import Icon from '../components/Icon.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, ApiError, OfflineError, type Chore, type Household, type Invite, type Me, type Member, type NewInvite, type ServerConfig, type UserHit } from '../lib/api';
  import { t, type Lang } from '../lib/i18n';
  import { navigate } from '../lib/router';
  import { toast } from '../lib/snack';
  import { errorText, expiresIn, memberDirectory } from '../lib/words';

  let { hid, config, me, lang }: { hid: number; config: ServerConfig; me: Me; lang: Lang } = $props();

  let household: Household | null = $state(null);
  let members: Member[] = $state([]);
  let invites: Invite[] = $state([]);
  let archived: Chore[] = $state([]);
  let phase: 'loading' | 'ready' | 'failed' | 'gone' = $state('loading');
  let offline = $state(false);
  let name = $state('');
  let directory = $state(false);
  let who = $state('');
  let hits: UserHit[] = $state([]);
  let searched = $state('');
  let addError: string | null = $state(null);
  let newInvite: NewInvite | null = $state(null);
  let busy = $state(false);
  let removing: Member | null = $state(null);
  let leaving = $state(false);
  let lastMember = $state(false);
  let now = $state(new Date());

  const canShare = typeof navigator !== 'undefined' && 'share' in navigator;
  const others = $derived(members.filter((m) => m.id !== me.id));

  async function load() {
    try {
      const [h, m, i, a] = await Promise.all([api.household(hid), api.members(hid), api.invites(hid), api.chores(hid, { archived: true })]);
      household = h;
      if (phase !== 'ready') name = h.name;
      members = m;
      invites = i;
      archived = a;
      now = new Date();
      offline = false;
      phase = 'ready';
    } catch (e) {
      if (e instanceof ApiError && (e.status === 404 || e.status === 403)) phase = 'gone';
      else {
        offline = e instanceof OfflineError;
        if (phase === 'loading') phase = 'failed';
      }
    }
  }

  async function run(f: () => Promise<unknown>, done?: string): Promise<boolean> {
    busy = true;
    try {
      await f();
      if (done) toast(done);
      return true;
    } catch (e) {
      toast(errorText(e, lang, 'done.connect'));
      return false;
    } finally {
      busy = false;
    }
  }

  async function rename(e: SubmitEvent) {
    e.preventDefault();
    const n = name.trim();
    if (!n || n === household?.name) return;
    await run(async () => (household = await api.renameHousehold(hid, n)), t('hh.renamed'));
  }

  let searchTimer: ReturnType<typeof setTimeout> | null = null;
  let searchSeq = 0;

  function typed() {
    addError = null;
    if (!directory) return;
    if (searchTimer) clearTimeout(searchTimer);
    const q = who.trim();
    if (q.length < 2) {
      hits = [];
      searched = '';
      return;
    }
    const s = ++searchSeq;
    searchTimer = setTimeout(async () => {
      try {
        const r = await api.searchUsers(q);
        if (s !== searchSeq) return;
        hits = r;
        searched = q;
      } catch {
        if (s === searchSeq) hits = [];
      }
    }, 200);
  }

  async function add(target: { username: string } | { user_id: number }) {
    addError = null;
    busy = true;
    try {
      const m = await api.addMember(hid, target);
      toast(t('hh.added', { name: m.display_name }));
      who = '';
      hits = [];
      searched = '';
      await load();
    } catch (e) {
      addError = errorText(e, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  function addByName(e: SubmitEvent) {
    e.preventDefault();
    const u = who.trim();
    if (u) add({ username: u });
  }

  async function remove() {
    const m = removing;
    removing = null;
    if (m && (await run(() => api.removeMember(hid, m.id), t('hh.removed', { name: m.display_name })))) await load();
  }

  function askLeave() {
    lastMember = members.length <= 1;
    leaving = true;
  }

  async function leave() {
    busy = true;
    try {
      await api.leaveHousehold(hid, lastMember);
      leaving = false;
      navigate('/households', { replace: true });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'last_member_confirm') lastMember = true;
      else {
        leaving = false;
        toast(errorText(e, lang, 'done.connect'));
      }
    } finally {
      busy = false;
    }
  }

  async function createInvite() {
    await run(async () => {
      newInvite = await api.createInvite(hid);
      invites = await api.invites(hid);
      now = new Date();
    });
  }

  async function copy() {
    if (!newInvite) return;
    try {
      await navigator.clipboard.writeText(newInvite.url);
      toast(t('hh.copied'));
    } catch {
      document.querySelector<HTMLInputElement>('.new-link input')?.select();
    }
  }

  async function share() {
    if (!newInvite || !household) return;
    try {
      await navigator.share({ title: t('hh.share_text', { household: household.name, app: config.name }), url: newInvite.url });
    } catch {
      /* dismissed */
    }
  }

  async function cancelInvite(i: Invite) {
    if (await run(() => api.cancelInvite(hid, i.id), t('hh.invite_cancelled'))) {
      if (newInvite?.id === i.id) newInvite = null;
      invites = invites.filter((x) => x.id !== i.id);
    }
  }

  async function restore(c: Chore) {
    if (await run(() => api.restoreChore(c.id), t('hh.restored', { name: c.name }))) archived = archived.filter((x) => x.id !== c.id);
  }

  onMount(() => {
    load();
    memberDirectory((q) => api.searchUsers(q)).then((d) => (directory = d));
    return () => {
      if (searchTimer) clearTimeout(searchTimer);
    };
  });
</script>

<PageBar title={t('hh.title')} back="/households/{hid}" />

<main class="screen">
  {#if phase === 'loading'}
    <p data-copy class="muted" aria-busy="true">{t('common.loading')}</p>
  {:else if phase === 'gone'}
    <div class="state">
      <Icon name="circle-alert" size={28} />
      <p data-copy>{t('error.not_found')}</p>
      <a class="btn" href="/households">{t('nav.households')}</a>
    </div>
  {:else if phase === 'failed'}
    <div class="state">
      <Icon name={offline ? 'wifi-off' : 'circle-alert'} size={28} />
      <p data-copy>{t(offline ? 'error.offline' : 'error.generic')}</p>
      <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
    </div>
  {:else if household}
    <form class="rename section" onsubmit={rename}>
      <label class="section-title" data-copy for="hh-name">{t('hh.name')}</label>
      <div class="inline">
        <input id="hh-name" class="input" maxlength="60" required autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" bind:value={name} />
        <button class="btn" type="submit" disabled={busy || !name.trim() || name.trim() === household.name}>{t('hh.save')}</button>
      </div>
    </form>

    <section class="section">
      <h2 data-copy class="section-title">{t('hh.members')}</h2>
      <ul class="rows card members">
        {#each members as m (m.id)}
          <li class="member">
            <Avatar name={m.display_name} picture={m.picture_url} size={36} />
            <span class="who">
              <span class="name" data-nowrap>{m.display_name}{#if m.id === me.id}<span class="you">{` (${t('hh.you')})`}</span>{/if}</span>
              {#if m.username}<span class="user" data-nowrap>@{m.username}</span>{/if}
            </span>
            {#if m.id !== me.id}
              <button class="btn btn-sm" type="button" aria-label={t('hh.remove_label', { name: m.display_name })} onclick={() => (removing = m)}>{t('hh.remove')}</button>
            {/if}
          </li>
        {/each}
      </ul>
      <div class="add card card-pad">
        <form onsubmit={addByName}>
          <label class="label" for="add-member">{t('hh.add')}</label>
          <div class="inline">
            <input
              id="add-member"
              class="input"
              maxlength="60"
              autocomplete="off"
              autocorrect="off"
              autocapitalize="off"
              spellcheck="false"
              placeholder={t(directory ? 'hh.add_search' : 'hh.add_hint_exact')}
              bind:value={who}
              oninput={typed}
            />
            {#if !directory}
              <button class="btn" type="submit" aria-label={t('hh.add_button')} disabled={busy || !who.trim()}><Icon name="user-plus" size={18} /><span class="wide">{t('hh.add_button')}</span></button>
            {/if}
          </div>
        </form>
        {#if addError}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{addError}</span></div>{/if}
        {#if directory && who.trim().length >= 2}
          {#if hits.length}
            <ul class="rows hits">
              {#each hits as h (h.id)}
                {@const member = members.some((m) => m.id === h.id)}
                <li class="member">
                  <Avatar name={h.display_name} picture={h.picture_url} size={32} />
                  <span class="who">
                    <span class="name" data-nowrap>{h.display_name}</span>
                    {#if h.username}<span class="user" data-nowrap>@{h.username}</span>{/if}
                  </span>
                  {#if member}
                    <Icon name="check" size={18} />
                  {:else}
                    <button class="btn btn-sm" type="button" aria-label="{t('hh.add_button')} {h.display_name}" disabled={busy} onclick={() => add({ user_id: h.id })}>{t('hh.add_button')}</button>
                  {/if}
                </li>
              {/each}
            </ul>
          {:else if searched === who.trim()}
            <p data-copy class="muted small">{t('hh.no_hits')}</p>
          {/if}
        {/if}
      </div>
    </section>

    <section class="section">
      <h2 data-copy class="section-title">{t('hh.invites')}</h2>
      <div class="card card-pad">
        <p data-copy class="muted small">{t('hh.invite_hint')}</p>
        {#if newInvite}
          <div class="new-link">
            <input class="input" readonly value={newInvite.url} aria-label={t('hh.invites')} onfocus={(e) => e.currentTarget.select()} />
            <div class="link-actions">
              <button class="btn" type="button" onclick={copy}><Icon name="copy" size={18} />{t('hh.copy')}</button>
              {#if canShare}<button class="btn" type="button" onclick={share}><Icon name="share-2" size={18} />{t('hh.share')}</button>{/if}
            </div>
          </div>
        {/if}
        <button class="btn" type="button" disabled={busy} onclick={createInvite}><Icon name="link" size={18} />{t('hh.invite_create')}</button>
      </div>
      {#if invites.length}
        <ul class="rows card">
          {#each invites as i (i.id)}
            <li class="invite">
              <Icon name="link" size={18} />
              <span class="who">
                <span class="name plain" data-nowrap>{t('hh.invite_by', { name: i.created_by.display_name })}</span>
                <span class="user" data-nowrap>{expiresIn(i.expires_at, now, lang)}</span>
              </span>
              <button class="btn btn-sm" type="button" aria-label={t('hh.invite_cancel_label', { name: i.created_by.display_name })} onclick={() => cancelInvite(i)}><Icon name="x" size={16} /><span class="wide">{t('hh.invite_cancel')}</span></button>
            </li>
          {/each}
        </ul>
      {:else}
        <p data-copy class="muted small pad">{t('hh.no_invites')}</p>
      {/if}
    </section>

    <section class="section">
      <h2 data-copy class="section-title">{t('hh.archived')}</h2>
      {#if archived.length}
        <ul class="rows card">
          {#each archived as c (c.id)}
            <li class="invite">
              <span class="who"><span class="name" data-nowrap>{c.name}</span>{#if c.category}<span class="user" data-nowrap>{c.category}</span>{/if}</span>
              <button class="btn btn-sm" type="button" aria-label="{t('chore.restore')}: {c.name}" onclick={() => restore(c)}><Icon name="rotate-ccw" size={16} /><span class="wide">{t('chore.restore')}</span></button>
            </li>
          {/each}
        </ul>
      {:else}
        <p data-copy class="muted small pad">{t('hh.no_archived')}</p>
      {/if}
    </section>

    <section class="section leave">
      <button class="btn btn-danger" type="button" onclick={askLeave}><Icon name="log-out" size={18} />{t('hh.leave')}</button>
    </section>
  {/if}
</main>

<ConfirmSheet
  open={removing !== null}
  title={t('hh.remove_title', { name: removing?.display_name ?? '' })}
  text={t('hh.remove_text')}
  confirm={t('hh.remove')}
  cancel={t('hh.keep')}
  danger
  {busy}
  onconfirm={remove}
  onclose={() => (removing = null)}
/>

<ConfirmSheet
  open={leaving}
  title={t(lastMember ? 'hh.leave_last_title' : 'hh.leave_title', { name: household?.name ?? '' })}
  text={t(lastMember ? 'hh.leave_last_text' : 'hh.leave_text')}
  confirm={t(lastMember ? 'hh.delete_confirm' : 'hh.leave_confirm')}
  cancel={t('hh.keep')}
  danger
  {busy}
  onconfirm={leave}
  onclose={() => (leaving = false)}
/>

<style>
  .inline {
    display: flex;
    gap: var(--s2);
  }

  .inline .input {
    flex: 1;
    min-width: 0;
  }

  .label {
    display: block;
    margin-bottom: var(--s2);
    font-size: var(--fs-sm);
    font-weight: 500;
  }

  .member,
  .invite {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    align-items: center;
    gap: var(--s3);
    min-height: 60px;
    padding: var(--s2) var(--s3) var(--s2) var(--s4);
  }

  .hits .member {
    padding-inline: 0;
    min-height: 52px;
  }

  .who {
    display: flex;
    flex: 1 1 6em;
    flex-direction: column;
    min-width: 0;
  }

  .name {
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  @media (max-width: 400px) {
    .wide {
      display: none;
    }
  }

  .plain {
    font-weight: 500;
  }

  .invite > :global(svg) {
    flex: none;
    color: var(--muted);
  }

  .you {
    font-weight: 400;
    color: var(--muted);
  }

  .user {
    font-size: var(--fs-sm);
    color: var(--muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .member :global(svg) {
    color: var(--accent);
  }

  .new-link {
    display: flex;
    flex-direction: column;
    gap: var(--s2);
  }

  .new-link .input {
    font-size: var(--fs-sm);
    font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace;
  }

  .link-actions {
    display: flex;
    gap: var(--s2);
  }

  .link-actions .btn {
    flex: 1;
  }

  .pad {
    padding: 0 var(--s1);
  }

  .leave {
    align-items: flex-start;
    padding-top: var(--s2);
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
</style>
