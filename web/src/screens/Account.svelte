<script lang="ts">
  import { untrack } from 'svelte';
  import Avatar from '../components/Avatar.svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import Icon from '../components/Icon.svelte';
  import LegalLinks from '../components/LegalLinks.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, type Me, type ServerConfig } from '../lib/api';
  import { t, type Lang } from '../lib/i18n';
  import { toast } from '../lib/snack';
  import { canConfirmDelete, errorText, passwordProblem } from '../lib/words';

  let {
    config,
    me,
    lang,
    onlogout,
    onme,
    ondeleted,
  }: { config: ServerConfig; me: Me; lang: Lang; onlogout: () => void; onme: (m: Me) => void; ondeleted: () => void } = $props();

  let name = $state(untrack(() => me.display_name));
  let current = $state('');
  let pw = $state('');
  let repeat = $state('');
  let busy = $state(false);
  let nameError: string | null = $state(null);
  let pwError: string | null = $state(null);
  let deleting = $state(false);
  let typed = $state('');
  let deleteError: string | null = $state(null);

  const sso = $derived(me.kind === 'sso');
  const manage = $derived(me.account_url ?? config.oidc?.account_url ?? null);
  const canDelete = $derived(canConfirmDelete(me, typed));

  async function saveName(e: SubmitEvent) {
    e.preventDefault();
    const n = name.trim();
    if (!n || n === me.display_name || busy) return;
    busy = true;
    nameError = null;
    try {
      onme(await api.patchMe({ display_name: n }));
      toast(t('account.name_saved'));
    } catch (err) {
      nameError = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  async function savePassword(e: SubmitEvent) {
    e.preventDefault();
    if (busy) return;
    const p = passwordProblem(pw, repeat);
    pwError = p === 'short' ? t('error.password_too_short') : p === 'mismatch' ? t('account.mismatch') : null;
    if (p) return;
    busy = true;
    try {
      await api.patchMe({ current_password: current, new_password: pw });
      current = pw = repeat = '';
      toast(t('account.pw_saved'));
    } catch (err) {
      pwError = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  async function remove() {
    if (!canDelete || busy) return;
    busy = true;
    deleteError = null;
    try {
      await api.deleteMe(sso ? { confirm_username: typed.trim() } : { current_password: typed });
      deleting = false;
      ondeleted();
    } catch (err) {
      deleteError = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  function closeDelete() {
    deleting = false;
    typed = '';
    deleteError = null;
  }
</script>

<PageBar title={t('account.title')} back={me.last_household_id ? `/households/${me.last_household_id}` : '/households'} />

<main class="screen">
  <section class="profile card card-pad">
    <Avatar name={me.display_name} picture={me.picture_url} size={64} />
    <div class="who">
      <p class="name clamp" data-clamp="2">{me.display_name}</p>
      {#if me.username}<p class="user clamp" data-clamp="2">@{me.username}</p>{/if}
    </div>
  </section>

  {#if sso}
    <section class="section">
      {#if config.oidc}<p data-copy class="muted small pad">{t('account.managed_by', { name: config.oidc.name })}</p>{/if}
      {#if manage}
        <a class="btn" href={manage} target="_blank" rel="noopener noreferrer" data-external>{t('account.manage')}<Icon name="external-link" size={16} /></a>
      {/if}
    </section>
  {:else}
    <form class="section name-form" onsubmit={saveName}>
      <label class="section-title" data-copy for="account-name">{t('account.name')}</label>
      <div class="card card-pad">
        {#if nameError}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{nameError}</span></div>{/if}
        <input id="account-name" class="input" maxlength="60" required autocomplete="nickname" autocorrect="off" autocapitalize="off" spellcheck="false" bind:value={name} />
        <button class="btn" type="submit" disabled={busy || !name.trim() || name.trim() === me.display_name}>{t('account.name_save')}</button>
      </div>
    </form>

    <form class="section pw-form" onsubmit={savePassword}>
      <h2 data-copy class="section-title">{t('account.change_pw')}</h2>
      <div class="card card-pad">
        {#if pwError}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{pwError}</span></div>{/if}
        <input class="visually-hidden" type="text" name="username" autocomplete="username" value={me.username ?? ''} readonly tabindex="-1" aria-hidden="true" />
        <div class="field">
          <label for="pw-current">{t('account.current')}</label>
          <input id="pw-current" class="input" type="password" autocomplete="current-password" required bind:value={current} />
        </div>
        <div class="field">
          <label for="pw-new">{t('account.new')}</label>
          <input id="pw-new" class="input" type="password" autocomplete="new-password" minlength="12" required bind:value={pw} />
          <span class="hint" data-copy>{t('login.new_pw_hint')}</span>
        </div>
        <div class="field">
          <label for="pw-repeat">{t('account.repeat')}</label>
          <input id="pw-repeat" class="input" type="password" autocomplete="new-password" required bind:value={repeat} />
        </div>
        <button class="btn" type="submit" disabled={busy || !current || !pw || !repeat}>{t('account.pw_save')}</button>
      </div>
    </form>
  {/if}

  <section class="section">
    <button class="btn" type="button" onclick={onlogout}><Icon name="log-out" size={18} />{t('nav.logout')}</button>
  </section>

  <section class="section danger">
    <h2 data-copy class="section-title">{t('account.delete')}</h2>
    <p data-copy class="muted small pad">{t('account.delete_text')}</p>
    <button class="btn btn-danger" type="button" onclick={() => (deleting = true)}><Icon name="trash-2" size={18} />{t('account.delete')}</button>
    <LegalLinks />
  </section>
</main>

<ConfirmSheet
  open={deleting}
  title={t('account.delete_title')}
  text={t('account.delete_text')}
  confirm={t('account.delete_button')}
  cancel={t('account.keep')}
  danger
  disabled={!canDelete}
  {busy}
  onconfirm={remove}
  onclose={closeDelete}
>
  <div class="field">
    {#if sso}
      <label for="delete-confirm">{t('account.delete_type_sso', { name: me.display_name })}</label>
      <input id="delete-confirm" class="input" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" bind:value={typed} />
    {:else}
      <label for="delete-confirm">{t('account.delete_password')}</label>
      <input id="delete-confirm" class="input" type="password" autocomplete="current-password" bind:value={typed} />
    {/if}
  </div>
  {#if deleteError}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{deleteError}</span></div>{/if}
</ConfirmSheet>

<style>
  .profile {
    flex-direction: row;
    align-items: center;
  }

  .who {
    min-width: 0;
  }

  .name {
    font-size: var(--fs-lg);
    font-weight: 650;
  }

  .user {
    color: var(--muted);
  }

  .section > .btn {
    align-self: flex-start;
  }

  .card-pad > .btn {
    align-self: flex-start;
  }

  .pad {
    padding: 0 var(--s1);
  }

  .danger {
    padding-top: var(--s4);
    border-top: 1px solid var(--border);
  }
</style>
