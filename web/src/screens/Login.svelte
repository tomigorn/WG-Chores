<script lang="ts">
  import type { Snippet } from 'svelte';
  import Icon from '../components/Icon.svelte';
  import { api, ApiError, OfflineError, ssoStartUrl, type ServerConfig } from '../lib/api';
  import { t } from '../lib/i18n';

  let {
    config,
    invite = null,
    serverError = null,
    registration = null,
    header,
    onSignedIn,
  }: {
    config: ServerConfig;
    invite?: string | null;
    serverError?: string | null;
    registration?: boolean | null;
    header?: Snippet;
    onSignedIn: (mode: 'login' | 'register') => void;
  } = $props();

  const PW_AUTOCOMPLETE = { login: 'current-password', register: 'new-password' } as const;
  const SERVER_ERRORS = ['not_allowed', 'sso_failed', 'sso_unavailable'];

  let mode: 'login' | 'register' = $state('login');
  let username = $state('');
  let password = $state('');
  let displayName = $state('');
  let busy = $state(false);
  let failure: string | null = $state(null);

  const canRegister = $derived(registration ?? (config.local_accounts && (config.open_registration || !!invite)));
  const shown = $derived(
    failure ?? (serverError ? t(SERVER_ERRORS.includes(serverError) ? `error.${serverError}` : 'error.generic') : null),
  );

  function message(e: unknown): string {
    if (e instanceof OfflineError) return t('error.offline');
    if (e instanceof ApiError) {
      const m = t(`error.${e.code}`);
      return m === `error.${e.code}` ? t('error.generic') : m;
    }
    return t('error.generic');
  }

  async function submit(ev: SubmitEvent) {
    ev.preventDefault();
    if (busy) return;
    busy = true;
    failure = null;
    try {
      if (mode === 'login') await api.login(username.trim(), password);
      else
        await api.register({
          username: username.trim(),
          password,
          display_name: displayName.trim(),
          ...(invite ? { invite } : {}),
        });
      onSignedIn(mode);
    } catch (e) {
      failure = message(e);
      password = '';
    } finally {
      busy = false;
    }
  }

  function switchMode() {
    mode = mode === 'login' ? 'register' : 'login';
    failure = null;
  }
</script>

<main class="login">
  {#if header}
    {@render header()}
  {:else}
    <header class="brand">
      <span class="mark"><Icon name="list-checks" size={24} /></span>
      <h1>{t('app.name')}</h1>
      <p class="tagline">{t('app.tagline')}</p>
    </header>
  {/if}

  <section class="card" aria-labelledby="login-title">
    <h2 id="login-title">{mode === 'login' ? t('login.title') : t('login.register_title')}</h2>

    {#if invite && !header}
      <p class="note">{t('login.invite_note')}</p>
    {/if}

    {#if shown}
      <div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{shown}</span></div>
    {/if}

    {#if config.oidc && mode === 'login'}
      <a class="btn btn-primary btn-block sso" href={ssoStartUrl(invite)} data-external>
        <span class="ellipsis">{t('login.sso', { name: config.oidc.name })}</span>
      </a>
    {/if}

    {#if config.oidc && config.local_accounts && mode === 'login'}
      <div class="or" role="separator"><span>{t('login.or')}</span></div>
    {/if}

    {#if config.local_accounts}
      <form onsubmit={submit}>
        {#if mode === 'register'}
          <div class="field">
            <label for="display-name">{t('login.display_name')}</label>
            <input id="display-name" class="input" name="display_name" autocomplete="nickname" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="60" required bind:value={displayName} />
            <span class="hint">{t('login.display_name_hint')}</span>
          </div>
        {/if}
        <div class="field">
          <label for="username">{t('login.username')}</label>
          <input id="username" class="input" name="username" autocomplete="username" autocorrect="off" autocapitalize="off" spellcheck="false" maxlength="32" required bind:value={username} />
        </div>
        <div class="field">
          <label for="password">{t('login.pw')}</label>
          <input id="password" class="input" type="password" name="password" autocomplete={PW_AUTOCOMPLETE[mode]} minlength={mode === 'register' ? 12 : undefined} required bind:value={password} />
          {#if mode === 'register'}<span class="hint">{t('login.new_pw_hint')}</span>{/if}
        </div>
        <button class="btn btn-block" class:btn-primary={!config.oidc || mode === 'register'} type="submit" disabled={busy}>
          {mode === 'login' ? t('login.submit') : t('login.register_submit')}
        </button>
      </form>
    {/if}

    {#if !config.oidc && !config.local_accounts}
      <p class="note">{t('login.nothing_enabled')}</p>
    {/if}
  </section>

  {#if invite && !header}
    <p class="switch"><a href="/invite/{invite}">{t('login.back_to_invite')}</a></p>
  {/if}

  {#if canRegister}
    <p class="switch">
      <button class="btn-link" type="button" onclick={switchMode}>
        {mode === 'login' ? t('login.create_account') : t('login.have_account')}
      </button>
    </p>
  {/if}
</main>

<style>
  .login {
    width: 100%;
    max-width: 400px;
    margin: 0 auto;
    padding: var(--s12) var(--s4) var(--s8);
    display: flex;
    flex-direction: column;
    gap: var(--s6);
  }

  .brand {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s2);
    text-align: center;
  }

  .mark {
    display: grid;
    place-items: center;
    width: 48px;
    height: 48px;
    border-radius: 12px;
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--accent);
    margin-bottom: var(--s1);
  }

  h1 {
    font-size: var(--fs-xl);
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  .tagline {
    color: var(--muted);
    font-size: var(--fs-sm);
  }

  .card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius);
    padding: var(--s6) var(--s4);
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  h2 {
    font-size: var(--fs-lg);
    font-weight: 650;
  }

  .note {
    color: var(--muted);
    font-size: var(--fs-sm);
  }

  form {
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  form .btn {
    margin-top: var(--s1);
  }

  .sso {
    min-width: 0;
  }

  .ellipsis {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    min-width: 0;
  }

  .or {
    display: flex;
    align-items: center;
    gap: var(--s3);
    color: var(--muted);
    font-size: var(--fs-sm);
  }

  .or::before,
  .or::after {
    content: '';
    flex: 1;
    border-top: 1px solid var(--border);
  }

  .switch {
    text-align: center;
  }
</style>
