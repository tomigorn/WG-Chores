<script lang="ts">
  import { onMount, untrack } from 'svelte';
  import Icon from '../components/Icon.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, OfflineError, type Household, type Me } from '../lib/api';
  import { t, type Lang } from '../lib/i18n';
  import { navigate } from '../lib/router';
  import { errorText } from '../lib/words';

  let { me, lang, url }: { me: Me; lang: Lang; url: URL } = $props();

  let list: Household[] = $state([]);
  let phase: 'loading' | 'ready' | 'failed' = $state('loading');
  let offline = $state(false);
  let creating = $state(untrack(() => url.searchParams.has('new')));
  let name = $state('');
  let busy = $state(false);
  let failure: string | null = $state(null);
  let input: HTMLInputElement | undefined = $state();

  $effect(() => {
    if (url.searchParams.has('new')) untrack(() => (creating = true));
  });

  $effect(() => {
    if (creating && input) input.focus();
  });

  async function load() {
    try {
      list = await api.households();
      phase = 'ready';
      offline = false;
    } catch (e) {
      offline = e instanceof OfflineError;
      if (phase === 'loading') phase = 'failed';
    }
  }

  async function create(e: SubmitEvent) {
    e.preventDefault();
    if (busy || !name.trim()) return;
    busy = true;
    failure = null;
    try {
      const h = await api.createHousehold(name.trim());
      navigate(`/households/${h.id}`, { replace: true });
    } catch (err) {
      failure = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  function cancel() {
    creating = false;
    name = '';
    failure = null;
    if (url.searchParams.has('new')) navigate('/households', { replace: true });
  }

  const plural = (n: number) => t(n === 1 ? 'households.members_one' : 'households.members_other', { n });

  onMount(load);
</script>

<PageBar title={t('households.title')} back={me.last_household_id ? `/households/${me.last_household_id}` : null} />

<main class="screen">
  {#if phase === 'loading'}
    <p class="muted" aria-busy="true">{t('common.loading')}</p>
  {:else if phase === 'failed'}
    <div class="state">
      <Icon name={offline ? 'wifi-off' : 'circle-alert'} size={28} />
      <p>{t(offline ? 'error.offline' : 'error.generic')}</p>
      <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
    </div>
  {:else}
    {#if list.length === 0 && !creating}
      <div class="state">
        <Icon name="house" size={32} />
        <h2 class="state-title">{t('households.empty_title')}</h2>
        <p>{t('households.empty_text')}</p>
      </div>
    {/if}
    {#if list.length}
      <ul class="rows card">
        {#each list as h (h.id)}
          <li class="hh-row">
            <a class="open" href="/households/{h.id}">
              <Icon name="house" size={20} />
              <span class="text">
                <span class="name" data-nowrap>{h.name}</span>
                <span class="count" data-nowrap>{plural(h.member_count)}</span>
              </span>
            </a>
            <a class="gear" href="/households/{h.id}/settings" aria-label={t('households.settings_for', { name: h.name })}><Icon name="settings" size={20} /></a>
          </li>
        {/each}
      </ul>
    {/if}

    <section class="new">
      {#if creating}
        <form class="card card-pad" onsubmit={create}>
          {#if failure}<div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{failure}</span></div>{/if}
          <div class="field">
            <label for="hh-name">{t('households.new')}</label>
            <input id="hh-name" class="input" maxlength="60" required autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" placeholder={t('households.name_placeholder')} bind:value={name} bind:this={input} />
          </div>
          <div class="actions">
            <button class="btn" type="button" onclick={cancel}>{t('households.cancel')}</button>
            <button class="btn btn-primary" type="submit" disabled={busy || !name.trim()}>{t('households.create')}</button>
          </div>
        </form>
      {:else}
        <button class="btn" type="button" onclick={() => (creating = true)}><Icon name="plus" size={18} />{t('households.new')}</button>
      {/if}
    </section>
  {/if}
</main>

<style>
  .hh-row {
    display: flex;
    align-items: center;
  }

  .open {
    display: flex;
    flex: 1;
    align-items: center;
    gap: var(--s3);
    min-width: 0;
    min-height: 64px;
    padding: var(--s2) var(--s2) var(--s2) var(--s4);
    color: inherit;
    text-decoration: none;
  }

  .open > :global(svg) {
    color: var(--muted);
  }

  .text {
    display: flex;
    flex-direction: column;
    min-width: 0;
  }

  .name {
    font-weight: 600;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .count {
    font-size: var(--fs-sm);
    color: var(--muted);
    white-space: nowrap;
  }

  .gear {
    display: grid;
    flex: none;
    place-items: center;
    width: var(--tap);
    height: var(--tap);
    margin-right: var(--s2);
    border-radius: 50%;
    color: var(--muted);
  }

  .open:hover,
  .gear:hover {
    background: var(--bg);
  }

  .actions {
    display: flex;
    justify-content: flex-end;
    gap: var(--s2);
  }

  .state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--s3);
    padding: var(--s10) var(--s6) var(--s4);
    text-align: center;
    text-wrap: balance;
    color: var(--muted);
  }

  .state-title {
    font-size: var(--fs-lg);
    color: var(--text);
  }
</style>
