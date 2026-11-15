<script lang="ts">
  import { onMount } from 'svelte';
  import ConfirmSheet from '../components/ConfirmSheet.svelte';
  import Icon from '../components/Icon.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, type Category, type Chore, type ChoreInput, type Me, type ServerConfig } from '../lib/api';
  import type { IntervalCode } from '../lib/due';
  import { t, type Lang } from '../lib/i18n';
  import { navigate } from '../lib/router';
  import { toast } from '../lib/snack';
  import { errorText, INTERVAL_ORDER, intervalWord } from '../lib/words';

  let {
    choreId,
    householdId,
    lang,
  }: { choreId: number | null; householdId: number | null; config: ServerConfig; me: Me; lang: Lang } = $props();

  let chore = $state<Chore | null>(null);
  let hid: number | null = $state(null);
  let categories: Category[] = $state([]);
  let name = $state('');
  let category = $state('');
  let interval: IntervalCode | null = $state(null);
  let renameAll = $state(false);
  let phase: 'loading' | 'ready' | 'failed' = $state('loading');
  let busy = $state(false);
  let failure: string | null = $state(null);
  let confirmArchive = $state(false);

  const same = (a: string, b: string) => a.toLocaleLowerCase() === b.toLocaleLowerCase();
  const editing = $derived(choreId !== null);
  const original = $derived(chore?.category ?? null);
  const originalCount = $derived(original ? (categories.find((c) => same(c.name, original))?.chores ?? 0) : 0);
  const newCategory = $derived(category.trim());
  const intoOther = $derived(categories.some((c) => same(c.name, newCategory) && !(original && same(c.name, original))));
  const canRenameAll = $derived(!!original && !!newCategory && newCategory !== original && originalCount > 1 && !intoOther);
  const back = $derived(editing ? `/chores/${choreId}` : hid ? `/households/${hid}` : '/');

  async function load() {
    phase = 'loading';
    try {
      if (choreId !== null) {
        chore = await api.chore(choreId);
        hid = chore.household_id;
        name = chore.name;
        category = chore.category ?? '';
        interval = chore.interval;
      } else hid = householdId;
      categories = hid ? await api.categories(hid) : [];
      phase = 'ready';
    } catch (e) {
      failure = errorText(e, lang);
      phase = 'failed';
    }
  }

  function toggle(c: IntervalCode) {
    interval = interval === c ? null : c;
  }

  async function submit(e: SubmitEvent) {
    e.preventDefault();
    if (busy || !hid || !name.trim()) return;
    busy = true;
    failure = null;
    try {
      if (choreId === null) {
        const c = await api.createChore(hid, { name: name.trim(), category: newCategory || null, interval });
        toast(t('edit.created', { name: c.name }));
        navigate(`/households/${hid}`, { replace: true });
        return;
      }
      const patch: Partial<ChoreInput> = { name: name.trim(), interval };
      if (canRenameAll && renameAll) {
        categories = await api.renameCategory(hid, original!, newCategory);
        if (chore) chore = { ...chore, category: newCategory };
        renameAll = false;
      }
      patch.category = newCategory || null;
      await api.patchChore(choreId, patch);
      toast(t('edit.saved'));
      navigate(`/chores/${choreId}`, { replace: true });
    } catch (err) {
      failure = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  async function archive() {
    if (!chore || !hid) return;
    busy = true;
    try {
      await api.archiveChore(chore.id);
      confirmArchive = false;
      toast(t('edit.archived', { name: chore.name }));
      navigate(`/households/${hid}`, { replace: true });
    } catch (err) {
      confirmArchive = false;
      failure = errorText(err, lang, 'done.connect');
    } finally {
      busy = false;
    }
  }

  onMount(load);
</script>

<PageBar title={t(editing ? 'edit.edit_title' : 'edit.new_title')}>
  {#snippet left()}
    <a class="text-btn" href={back} aria-label={t('edit.cancel')}><span class="narrow-icon"><Icon name="x" size={22} /></span><span class="wide">{t('edit.cancel')}</span></a>
  {/snippet}
  {#snippet right()}
    <button class="text-btn" type="submit" form="chore-form" aria-label={t('edit.save')} disabled={busy || phase !== 'ready' || !name.trim()}><span class="narrow-icon"><Icon name="check" size={22} /></span><span class="wide">{t('edit.save')}</span></button>
  {/snippet}
</PageBar>

<main class="screen">
  {#if phase === 'loading'}
    <p data-copy class="muted" aria-busy="true">{t('common.loading')}</p>
  {:else if phase === 'failed'}
    <div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{failure}</span></div>
    <button class="btn" type="button" onclick={load}>{t('common.retry')}</button>
  {:else}
    <form id="chore-form" class="form" onsubmit={submit}>
      {#if failure}
        <div class="alert" role="alert"><Icon name="circle-alert" size={18} /><span>{failure}</span></div>
      {/if}
      <div class="field">
        <label for="chore-name">{t('edit.name')}</label>
        <input id="chore-name" class="input" maxlength="80" required autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" bind:value={name} />
      </div>
      <div class="field">
        <label for="chore-category">{t('edit.category')}</label>
        <input id="chore-category" class="input" list="chore-categories" maxlength="40" autocomplete="off" autocorrect="off" autocapitalize="off" spellcheck="false" bind:value={category} />
        <datalist id="chore-categories">
          {#each categories as c (c.name)}<option value={c.name}></option>{/each}
        </datalist>
        <span class="hint" data-copy>{t('edit.category_hint')}</span>
        {#if canRenameAll}
          <label class="check">
            <input id="rename-all" type="checkbox" bind:checked={renameAll} />
            <span>{t('edit.rename_all', { from: original!, to: newCategory, n: originalCount })}</span>
          </label>
        {/if}
      </div>
      <div class="field">
        <span class="label" id="how-often">{t('edit.how_often')}</span>
        <div class="chips" role="group" aria-labelledby="how-often">
          {#each INTERVAL_ORDER as c (c)}
            <button type="button" class="chip" aria-pressed={interval === c} onclick={() => toggle(c)} data-nowrap>{intervalWord(c, lang)}</button>
          {/each}
        </div>
        <span class="hint" data-copy>{t('edit.none_hint')}</span>
      </div>
      {#if editing}
        <div class="danger-zone">
          <button class="btn btn-danger" type="button" onclick={() => (confirmArchive = true)}><Icon name="archive" size={18} />{t('edit.archive')}</button>
          <span class="hint" data-copy>{t('edit.archive_hint')}</span>
        </div>
      {/if}
    </form>
  {/if}
</main>

<ConfirmSheet
  open={confirmArchive}
  title={t('edit.archive_title', { name: chore?.name ?? '' })}
  text={t('edit.archive_text')}
  confirm={t('edit.archive_confirm')}
  cancel={t('edit.cancel')}
  {busy}
  onconfirm={archive}
  onclose={() => (confirmArchive = false)}
/>

<style>
  .narrow-icon {
    display: none;
  }

  /* em here follows the bar's text size, so large text switches to icons too */
  @container (max-width: 25em) {
    .narrow-icon {
      display: contents;
    }

    .wide {
      display: none;
    }
  }

  .form {
    display: flex;
    flex-direction: column;
    gap: var(--s6);
  }

  .label {
    font-size: var(--fs-sm);
    font-weight: 500;
  }

  .chips {
    display: flex;
    flex-wrap: wrap;
    gap: var(--s2);
    margin: var(--s1) 0;
  }

  .chip {
    display: inline-flex;
    align-items: center;
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

  .chip[aria-pressed='true'] {
    background: var(--accent);
    border-color: var(--accent);
    color: var(--accent-text);
    font-weight: 600;
  }

  .check {
    display: flex;
    align-items: flex-start;
    gap: var(--s2);
    margin-top: var(--s2);
    font-size: var(--fs-sm);
    font-weight: 400;
    cursor: pointer;
  }

  .check input {
    flex: none;
    width: 20px;
    height: 20px;
    margin: 0;
    accent-color: var(--accent);
  }

  .danger-zone {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--s2);
    padding-top: var(--s4);
    border-top: 1px solid var(--border);
  }

  .danger-zone .hint {
    font-size: var(--fs-xs);
    color: var(--muted);
  }
</style>
