<script lang="ts">
  import { onMount } from 'svelte';
  import Avatar from '../components/Avatar.svelte';
  import Icon from '../components/Icon.svelte';
  import Login from './Login.svelte';
  import { api, ApiError, OfflineError, type InvitePreview, type Me, type ServerConfig } from '../lib/api';
  import { t, type Lang } from '../lib/i18n';
  import { navigate } from '../lib/router';
  import { toast } from '../lib/snack';
  import { expiresIn } from '../lib/words';

  let {
    invite,
    config,
    me,
    lang,
    onsignedin,
  }: { invite: string; config: ServerConfig; me: Me | null; lang: Lang; onsignedin: () => Promise<Me | null> } = $props();

  let preview: InvitePreview | null = $state(null);
  let invalid: string | null = $state(null);
  let failed: string | null = $state(null);
  let busy = $state(false);
  let joinError: string | null = $state(null);

  function message(e: unknown): string {
    if (e instanceof OfflineError) return t('error.offline');
    if (e instanceof ApiError) {
      const m = t(`error.${e.code}`);
      return m === `error.${e.code}` ? t('error.generic') : m;
    }
    return t('error.generic');
  }

  async function load() {
    failed = null;
    try {
      preview = await api.invitePreview(invite);
      invalid = preview.valid ? null : t(`error.invite_${preview.reason ?? 'not_found'}`);
    } catch (e) {
      if (e instanceof ApiError && e.status === 404) invalid = t('error.invite_not_found');
      else failed = message(e);
    }
  }

  async function join() {
    if (!preview || busy) return;
    busy = true;
    joinError = null;
    try {
      const r = await api.acceptInvite(invite);
      toast(t(r.already_member ? 'invite.already' : 'invite.joined', { household: preview.household_name }));
      navigate(`/households/${r.household_id}`, { replace: true });
    } catch (e) {
      joinError = message(e);
    } finally {
      busy = false;
    }
  }

  async function signedIn(mode: 'login' | 'register') {
    const now = await onsignedin();
    if (mode === 'register' && now?.last_household_id && preview) {
      toast(t('invite.joined', { household: preview.household_name }));
      navigate(`/households/${now.last_household_id}`, { replace: true });
    }
  }

  onMount(load);
</script>

{#snippet card()}
  <header class="invite card">
    <span class="mark"><Icon name="house" size={24} /></span>
    {#if preview}
      <p class="lead">{t('invite.preview', { by: preview.invited_by })}</p>
      <h1 class="household" data-nowrap>{preview.household_name}</h1>
      {#if preview.valid}<p class="expires" data-nowrap>{expiresIn(preview.expires_at, new Date(), lang)}</p>{/if}
    {:else}
      <h1 class="household">{t('invite.title')}</h1>
    {/if}
    {#if invalid}
      <div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{invalid} {t('invite.invalid_hint')}</span></div>
    {/if}
  </header>
{/snippet}

{#if failed}
  <main class="center">
    <Icon name="wifi-off" size={28} />
    <p>{failed}</p>
    <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
  </main>
{:else if !preview && !invalid}
  <main class="center" aria-busy="true"><p>{t('common.loading')}</p></main>
{:else if me || invalid}
  <main class="page">
    {@render card()}
    {#if me && !invalid && preview}
      {#if joinError}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{joinError}</span></div>{/if}
      <button class="btn btn-primary btn-block" type="button" disabled={busy} onclick={join}><span class="ellipsis">{t('invite.join', { household: preview.household_name })}</span></button>
      <p class="as"><Avatar name={me.display_name} picture={me.picture_url} size={24} /><span class="ellipsis">{t('invite.signed_in_as', { name: me.display_name })}</span></p>
    {:else}
      <a class="btn btn-block" href={me ? '/' : '/login'}>{me ? t('invite.go_home') : t('login.title')}</a>
    {/if}
  </main>
{:else}
  <Login {config} {invite} registration={config.local_accounts && !!preview?.can_register} onSignedIn={signedIn} header={card} />
{/if}

<style>
  .page {
    width: 100%;
    max-width: 400px;
    margin: 0 auto;
    padding: var(--s12) var(--s4) var(--s8);
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  .invite {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s1);
    padding: var(--s6) var(--s4);
    text-align: center;
  }

  .mark {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    margin-bottom: var(--s2);
    border-radius: 12px;
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--accent);
  }

  .lead,
  .expires {
    color: var(--muted);
    font-size: var(--fs-sm);
  }

  .household {
    max-width: 100%;
    font-size: var(--fs-xl);
    font-weight: 700;
    letter-spacing: -0.01em;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .invite .alert {
    margin-top: var(--s3);
    text-align: left;
  }

  .as {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--s2);
    color: var(--muted);
    font-size: var(--fs-sm);
  }

  .center {
    min-height: 100dvh;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--s4);
    padding: var(--s6);
    text-align: center;
    color: var(--muted);
  }
</style>
