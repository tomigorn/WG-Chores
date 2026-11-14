<script lang="ts">
  import { untrack } from 'svelte';
  import Icon from '../components/Icon.svelte';
  import PageBar from '../components/PageBar.svelte';
  import { api, OfflineError, type Me } from '../lib/api';
  import { currentLanguage, languageSetting, setLanguage, t, type Lang, type LangSetting } from '../lib/i18n';
  import { push } from '../lib/push';
  import { toast } from '../lib/snack';
  import type { ThemeSetting } from '../lib/theme';

  let { me, theme, ontheme, onme }: { me: Me; lang: Lang; theme: ThemeSetting; ontheme: (s: ThemeSetting) => void; onme: (m: Me) => void } = $props();

  let language: LangSetting = $state(languageSetting());
  let notify = $state(untrack(() => ({ done: me.notify_done, due: me.notify_due })));
  let pushState = $state(push.status());
  const blocked = $derived(pushState === 'blocked');
  const needsInstall = $derived(pushState === 'needs-install');

  const THEMES: ThemeSetting[] = ['system', 'light', 'dark'];
  const LANGS: { v: LangSetting; key: string }[] = [
    { v: 'system', key: 'settings.lang_system' },
    { v: 'en', key: 'settings.lang_en' },
    { v: 'de', key: 'settings.lang_de' },
  ];

  function chooseLanguage(v: LangSetting) {
    language = v;
    setLanguage(v);
  }

  async function toggle(kind: 'done' | 'due', on: boolean, input: HTMLInputElement) {
    notify[kind] = on;
    try {
      if (on && pushState !== 'unsupported') {
        const r = await push.enable(currentLanguage());
        if (r === 'blocked' || r === 'dismissed') {
          if (r === 'blocked') pushState = 'blocked';
          notify[kind] = false;
          input.checked = false;
          return;
        }
        pushState = push.status();
      }
      onme(await api.patchMe(kind === 'done' ? { notify_done: on } : { notify_due: on }));
      if (!notify.done && !notify.due && pushState === 'ready') push.disable().catch(() => {});
    } catch (e) {
      notify[kind] = !on;
      toast(t(e instanceof OfflineError ? 'done.connect' : 'done.failed'));
    }
  }
</script>

<PageBar title={t('settings.title')} back={me.last_household_id ? `/households/${me.last_household_id}` : '/households'} />

<main class="screen">
  <section class="section">
    <h2 class="section-title" id="theme-title">{t('settings.theme')}</h2>
    <div class="seg" role="radiogroup" aria-label={t('settings.theme')}>
      {#each THEMES as s (s)}
        <button type="button" role="radio" aria-checked={theme === s} onclick={() => ontheme(s)}>{t(`theme.${s}`)}</button>
      {/each}
    </div>
  </section>

  <section class="section">
    <h2 class="section-title">{t('settings.language')}</h2>
    <div class="seg" role="radiogroup" aria-label={t('settings.language')}>
      {#each LANGS as l (l.v)}
        <button type="button" role="radio" aria-checked={language === l.v} lang={l.v === 'system' ? undefined : l.v} onclick={() => chooseLanguage(l.v)}>{t(l.key)}</button>
      {/each}
    </div>
  </section>

  <section class="section">
    <h2 class="section-title">{t('settings.notifications')}</h2>
    <ul class="rows card">
      {#each [{ k: 'done', label: 'settings.notify_done', hint: 'settings.notify_done_hint' }, { k: 'due', label: 'settings.notify_due', hint: 'settings.notify_due_hint' }] as const as n (n.k)}
        <li>
          <label class="toggle" for="notify-{n.k}">
            <span class="text">
              <span class="label">{t(n.label)}</span>
              <span class="hint">{t(n.hint)}</span>
            </span>
            <input id="notify-{n.k}" type="checkbox" role="switch" checked={notify[n.k] && !blocked} disabled={needsInstall} onchange={(e) => toggle(n.k, e.currentTarget.checked, e.currentTarget)} />
          </label>
        </li>
      {/each}
    </ul>
    {#if blocked}
      <p class="note"><Icon name="bell-off" size={16} /><span>{t('settings.blocked')}</span></p>
    {:else if needsInstall}
      <p class="note"><Icon name="smartphone" size={16} /><span>{t('settings.install_first')}</span></p>
    {:else}
      <p class="note"><Icon name="bell" size={16} /><span>{t('settings.notify_note')}</span></p>
    {/if}
  </section>
</main>

<style>
  .toggle {
    display: flex;
    align-items: center;
    gap: var(--s4);
    min-height: 64px;
    padding: var(--s3) var(--s4);
    cursor: pointer;
  }

  .text {
    display: flex;
    flex: 1;
    flex-direction: column;
    min-width: 0;
  }

  .label {
    font-weight: 500;
  }

  .hint {
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  input[role='switch'] {
    appearance: none;
    position: relative;
    flex: none;
    width: 44px;
    height: 26px;
    margin: 0;
    border-radius: 13px;
    border: 1px solid var(--border-strong);
    background: var(--bg);
    cursor: pointer;
    transition: background-color var(--motion) ease, border-color var(--motion) ease;
  }

  input[role='switch']::before {
    content: '';
    position: absolute;
    top: 2px;
    left: 2px;
    width: 20px;
    height: 20px;
    border-radius: 50%;
    background: var(--border-strong);
    transition: transform var(--motion) ease, background-color var(--motion) ease;
  }

  input[role='switch']:disabled {
    opacity: 0.5;
    cursor: default;
  }

  input[role='switch']:checked {
    background: var(--accent);
    border-color: var(--accent);
  }

  input[role='switch']:checked::before {
    transform: translateX(18px);
    background: var(--accent-text);
  }

  .note {
    display: flex;
    align-items: flex-start;
    gap: var(--s2);
    padding: 0 var(--s1);
    font-size: var(--fs-sm);
    color: var(--muted);
  }

  .note :global(svg) {
    margin-top: 2px;
  }
</style>
