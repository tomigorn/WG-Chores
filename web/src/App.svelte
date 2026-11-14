<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './components/Icon.svelte';
  import Sheet from './components/Sheet.svelte';
  import Household from './screens/Household.svelte';
  import { startSync, stopSync, sync } from './lib/sync';
  import Login from './screens/Login.svelte';
  import { api, setUnauthenticatedHandler, type Me, type ServerConfig } from './lib/api';
  import { bootFailure } from './lib/boot';
  import { currentLanguage, onLanguage, t } from './lib/i18n';
  import { currentRoute, navigate, onRoute, rememberNext, startRouter, takeNext, type Route } from './lib/router';
  import { setTheme, themeSetting, type ThemeSetting } from './lib/theme';

  const start = currentRoute();
  let route: Route = $state(start.route);
  let url: URL = $state(start.url);
  let lang = $state(currentLanguage());
  let config: ServerConfig | null = $state(null);
  let me: Me | null | undefined = $state(undefined);
  let failed: 'error.offline' | 'error.generic' | null = $state(null);
  let theme: ThemeSetting = $state(themeSetting());

  function toLogin() {
    me = null;
    if (route.name === 'invite' || route.name === 'login') return;
    rememberNext(location.pathname + location.search);
    navigate('/login', { replace: true });
  }

  async function boot() {
    failed = null;
    try {
      config ??= await api.config();
      me = await api.me();
      // SSO returns to /, so the page wanted before sign-in is picked up here too
      const next = takeNext();
      if (route.name === 'login' || (route.name === 'home' && next !== '/')) navigate(next, { replace: true });
    } catch (e) {
      const f = bootFailure(e);
      if (f === 'login') me = null;
      else failed = f;
    }
  }

  async function signedIn() {
    me = await api.me().catch(() => null);
    if (me && route.name !== 'invite') navigate(takeNext(), { replace: true });
  }

  let unsynced = $state(0);

  async function logout() {
    const n = me ? await sync().prepareLogout() : 0;
    if (n > 0) {
      unsynced = n;
      return;
    }
    await signOut();
  }

  async function signOut() {
    unsynced = 0;
    if (me) await sync().discard();
    stopSync();
    try {
      await api.logout();
    } catch {
      /* the cookie stays only when offline; the server ends it after 30 idle days */
    } finally {
      me = null;
      navigate('/login', { replace: true });
    }
  }

  function chooseTheme(s: ThemeSetting) {
    theme = s;
    setTheme(s);
  }

  onMount(() => {
    setUnauthenticatedHandler(toLogin);
    startRouter();
    const offRoute = onRoute((r, u) => {
      route = r;
      url = u;
    });
    const offLang = onLanguage((l) => (lang = l));
    const online = () => failed && boot();
    addEventListener('online', online);
    boot();
    return () => {
      offRoute();
      offLang();
      removeEventListener('online', online);
    };
  });

  $effect.pre(() => {
    if (me) startSync(me.id);
  });

  $effect(() => {
    if (me && route.name === 'home') navigate(me.last_household_id ? `/households/${me.last_household_id}` : '/households', { replace: true });
  });

  function opened(hid: number) {
    if (me) me.last_household_id = hid;
  }

  const showLogin = $derived(me === null && config !== null && (route.name === 'login' || route.name === 'invite'));
</script>

{#key lang}
  {#if failed}
    <main class="center">
      <Icon name={failed === 'error.offline' ? 'wifi-off' : 'circle-alert'} size={28} />
      <p>{t(failed)}</p>
      <button class="btn" type="button" onclick={boot}>{t('common.retry')}</button>
    </main>
  {:else if showLogin && config}
    <Login
      {config}
      invite={route.name === 'invite' ? route.token : null}
      serverError={url.searchParams.get('error')}
      onSignedIn={signedIn}
    />
  {:else if me && config && route.name === 'household'}
    {#key route.id}
      <Household hid={route.id} {config} {me} {lang} {theme} ontheme={chooseTheme} onlogout={logout} onopened={opened} />
    {/key}
  {:else if me && route.name !== 'home'}
    <header class="bar">
      <a class="btn" href={me.last_household_id ? `/households/${me.last_household_id}` : '/'}><Icon name="house" size={18} />{t('nav.home')}</a>
      <button class="btn" type="button" onclick={logout}><Icon name="log-out" size={18} />{t('nav.logout')}</button>
    </header>
    <main class="placeholder">
      <p class="muted">{t('common.signed_in_as', { name: me.display_name })}</p>
      <p class="muted">{t('common.coming_soon')}</p>
      <div class="seg" role="group" aria-label={t('theme.label')}>
        {#each ['system', 'light', 'dark'] as const as s (s)}
          <button class="btn" class:on={theme === s} type="button" aria-pressed={theme === s} onclick={() => chooseTheme(s)}>{t(`theme.${s}`)}</button>
        {/each}
      </div>
    </main>
  {/if}
  <Sheet open={unsynced > 0} onclose={() => (unsynced = 0)} label={t('logout.title')}>
    <div class="confirm">
      <h2>{t('logout.title')}</h2>
      <p>{t(unsynced === 1 ? 'logout.unsynced_one' : 'logout.unsynced_other', { n: unsynced })}</p>
      <button class="btn btn-primary btn-block" type="button" onclick={signOut}>{t('logout.anyway')}</button>
      <button class="btn btn-block" type="button" onclick={() => (unsynced = 0)}>{t('logout.cancel')}</button>
    </div>
  </Sheet>
{/key}

<style>
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

  .bar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: var(--s3);
    padding: var(--s2) var(--s4);
    background: var(--surface);
    border-bottom: 1px solid var(--border);
  }

  .placeholder {
    padding: var(--s6) var(--s4);
    display: flex;
    flex-direction: column;
    gap: var(--s3);
  }

  .confirm {
    display: flex;
    flex-direction: column;
    gap: var(--s3);
  }

  .confirm h2 {
    font-size: var(--fs-md);
    font-weight: 650;
  }

  .confirm p {
    color: var(--muted);
    margin-bottom: var(--s2);
  }

  .muted {
    color: var(--muted);
  }

  .seg {
    display: flex;
    gap: var(--s2);
  }

  .seg .on {
    border-color: var(--accent);
    color: var(--accent);
  }
</style>
