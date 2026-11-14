<script lang="ts">
  import { onMount } from 'svelte';
  import Icon from './components/Icon.svelte';
  import Sheet from './components/Sheet.svelte';
  import SnackHost from './components/SnackHost.svelte';
  import Account from './screens/Account.svelte';
  import Chore from './screens/Chore.svelte';
  import ChoreEdit from './screens/ChoreEdit.svelte';
  import Household from './screens/Household.svelte';
  import HouseholdSettings from './screens/HouseholdSettings.svelte';
  import Households from './screens/Households.svelte';
  import Invite from './screens/Invite.svelte';
  import Login from './screens/Login.svelte';
  import Settings from './screens/Settings.svelte';
  import { watchDropped } from './lib/snack';
  import { startSync, stopSync, sync } from './lib/sync';
  import { api, setUnauthenticatedHandler, type Me, type ServerConfig } from './lib/api';
  import { bootFailure, cachedSession, forgetSession, rememberSession } from './lib/boot';
  import { push } from './lib/push';
  import { watchUpdates, type ContainerLike } from './lib/update';
  import UpdateBar from './components/UpdateBar.svelte';
  import { currentLanguage, onLanguage, t } from './lib/i18n';
  import { currentRoute, inviteParam, navigate, onRoute, rememberNext, startRouter, takeNext, type Route } from './lib/router';
  import { setTheme, themeSetting, type ThemeSetting } from './lib/theme';

  const start = currentRoute();
  let route: Route = $state(start.route);
  let url: URL = $state(start.url);
  let lang = $state(currentLanguage());
  let config: ServerConfig | null = $state(null);
  let me = $state<Me | null | undefined>(undefined);
  let failed: 'error.offline' | 'error.generic' | null = $state(null);
  let theme: ThemeSetting = $state(themeSetting());
  // started from the cached session while offline; refreshed once the network is back
  let stale = $state(false);
  let applyUpdate: (() => void) | null = $state(null);
  let pushChecked = false;

  function toLogin() {
    forgetSession();
    if (me) void sync().clearCache();
    me = null;
    if (route.name === 'invite' || route.name === 'login') return;
    rememberNext(location.pathname + location.search);
    navigate('/login', { replace: true });
  }

  async function boot() {
    failed = null;
    try {
      config = await api.config();
      me = await api.me();
      stale = false;
      rememberSession(config, me);
      if (!pushChecked) {
        pushChecked = true;
        void push.refresh(me.notify_done || me.notify_due, currentLanguage());
      }
      // SSO returns to /, so the page wanted before sign-in is picked up here too
      const next = takeNext();
      if (route.name === 'login' || (route.name === 'home' && next !== '/')) navigate(next, { replace: true });
    } catch (e) {
      const f = bootFailure(e);
      const cached = f === 'error.offline' && me === undefined ? cachedSession() : null;
      if (f === 'login') {
        forgetSession();
        me = null;
      } else if (cached) {
        config = cached.config;
        me = cached.me;
        stale = true;
      } else if (!stale) failed = f;
    }
  }

  async function signedIn(mode: 'login' | 'register' = 'login'): Promise<Me | null> {
    me = await api.me().catch(() => null);
    const invite = route.name === 'login' ? inviteParam(url) : null;
    if (me && invite && mode === 'login') navigate(`/invite/${invite}`, { replace: true });
    else if (me && route.name !== 'invite') navigate(takeNext(), { replace: true });
    return me;
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

  async function signOut(serverSide = true) {
    unsynced = 0;
    if (me) await sync().discard();
    forgetSession();
    stopSync();
    try {
      if (serverSide) await api.logout();
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
    const online = () => (failed || stale) && boot();
    if (import.meta.env.PROD && 'serviceWorker' in navigator) {
      watchUpdates(navigator.serviceWorker as unknown as ContainerLike, {
        onUpdate: (apply) => (applyUpdate = apply),
        reload: () => location.reload(),
        navigate: (p) => navigate(p),
      })
        .then((reg) => addEventListener('visibilitychange', () => document.visibilityState === 'visible' && reg.update().catch(() => {})))
        .catch(() => {});
    }
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

  const meId = $derived(me?.id ?? null);

  $effect(() => {
    if (meId === null) return;
    return watchDropped(startSync(meId).outbox, () => lang);
  });

  $effect(() => {
    if (me && route.name === 'home') navigate(me.last_household_id ? `/households/${me.last_household_id}` : '/households', { replace: true });
  });

  function opened(hid: number) {
    if (!me) return;
    me.last_household_id = hid;
    if (config) rememberSession(config, me);
  }

  function updated(m: Me) {
    me = m;
    if (config) rememberSession(config, m);
  }

  const showLogin = $derived(me === null && config !== null && route.name === 'login');
</script>

{#key lang}
  {#if failed}
    <main class="center">
      <Icon name={failed === 'error.offline' ? 'wifi-off' : 'circle-alert'} size={28} />
      <p>{t(failed)}</p>
      <button class="btn" type="button" onclick={boot}>{t('common.retry')}</button>
    </main>
  {:else if showLogin && config}
    <Login {config} invite={inviteParam(url)} serverError={url.searchParams.get('error')} onSignedIn={signedIn} />
  {:else if config && me !== undefined && route.name === 'invite'}
    <Invite invite={route.token} {config} {me} {lang} onsignedin={signedIn} />
  {:else if me && config}
    {#if route.name === 'household'}
      {#key route.id}
        <Household hid={route.id} {config} {me} {lang} {theme} ontheme={chooseTheme} onlogout={logout} onopened={opened} />
      {/key}
    {:else if route.name === 'households'}
      <Households {me} {lang} {url} />
    {:else if route.name === 'household-settings'}
      {#key route.id}<HouseholdSettings hid={route.id} {config} {me} {lang} />{/key}
    {:else if route.name === 'chore'}
      {#key route.id}<Chore id={route.id} {config} {me} {lang} />{/key}
    {:else if route.name === 'chore-edit'}
      {#key route.id}<ChoreEdit choreId={route.id} householdId={null} {config} {me} {lang} />{/key}
    {:else if route.name === 'new-chore'}
      {#key route.householdId}<ChoreEdit choreId={null} householdId={route.householdId} {config} {me} {lang} />{/key}
    {:else if route.name === 'settings'}
      <Settings {me} {lang} {theme} ontheme={chooseTheme} onme={updated} />
    {:else if route.name === 'account'}
      <Account {config} {me} {lang} onlogout={logout} onme={updated} ondeleted={() => signOut(false)} />
    {/if}
  {/if}
  <SnackHost />
  {#if applyUpdate}<UpdateBar onreload={applyUpdate} />{/if}
  <Sheet open={unsynced > 0} onclose={() => (unsynced = 0)} label={t('logout.title')}>
    <div class="confirm">
      <h2>{t('logout.title')}</h2>
      <p>{t(unsynced === 1 ? 'logout.unsynced_one' : 'logout.unsynced_other', { n: unsynced })}</p>
      <button class="btn btn-primary btn-block" type="button" onclick={() => signOut()}>{t('logout.anyway')}</button>
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
</style>
