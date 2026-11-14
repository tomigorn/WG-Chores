<script lang="ts">
  import Icon, { type IconName } from './Icon.svelte';
  import { t } from '../lib/i18n';
  import type { ThemeSetting } from '../lib/theme';

  let {
    open,
    onclose,
    theme,
    ontheme,
    onnavigate,
    onlogout,
    householdId = null,
  }: {
    open: boolean;
    onclose: () => void;
    theme: ThemeSetting;
    ontheme: (s: ThemeSetting) => void;
    onnavigate: (path: string) => void;
    onlogout: () => void;
    householdId?: number | null;
  } = $props();

  let panel: HTMLDivElement | undefined = $state();

  const items: { icon: IconName; key: string; path: string }[] = $derived([
    { icon: 'house', key: 'nav.households', path: '/households' },
    { icon: 'plus', key: 'menu.new_household', path: '/households?new=1' },
    ...(householdId ? [{ icon: 'users' as const, key: 'menu.household_settings', path: `/households/${householdId}/settings` }] : []),
    { icon: 'settings', key: 'nav.settings', path: '/settings' },
    { icon: 'user-round', key: 'nav.account', path: '/account' },
  ]);

  $effect(() => {
    if (!open || !panel) return;
    panel.querySelector<HTMLElement>('[role=menuitem]')?.focus();
    const outside = (e: PointerEvent) => {
      if (!panel?.contains(e.target as Node) && !(e.target as Element).closest?.('[data-menu-button]')) onclose();
    };
    document.addEventListener('pointerdown', outside, true);
    return () => document.removeEventListener('pointerdown', outside, true);
  });

  function keydown(e: KeyboardEvent) {
    if (e.key === 'Tab' || e.key === 'Escape') {
      e.preventDefault();
      onclose();
      document.querySelector<HTMLElement>('[data-menu-button]')?.focus();
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp') return;
    e.preventDefault();
    const all = [...(panel?.querySelectorAll<HTMLElement>('[role=menuitem], [role=menuitemradio]') ?? [])];
    const i = all.indexOf(document.activeElement as HTMLElement);
    all[(i + (e.key === 'ArrowDown' ? 1 : all.length - 1)) % all.length]?.focus();
  }

  function go(path: string) {
    onclose();
    onnavigate(path);
  }
</script>

{#if open}
  <!-- svelte-ignore a11y_interactive_supports_focus -->
  <div class="menu" role="menu" tabindex="-1" bind:this={panel} onkeydown={keydown} aria-label={t('menu.open')}>
    {#each items as it (it.key)}
      <button class="item" type="button" role="menuitem" onclick={() => go(it.path)}>
        <Icon name={it.icon} size={18} /><span data-nowrap>{t(it.key)}</span>
      </button>
    {/each}
    <div class="sep" role="separator"></div>
    <div class="theme" role="none">
      <Icon name="sun-moon" size={18} />
      <div class="seg" role="none">
        {#each ['system', 'light', 'dark'] as const as s (s)}
          <button type="button" role="menuitemradio" aria-checked={theme === s} aria-label="{t('theme.label')}: {t(`theme.${s}`)}" class:on={theme === s} onclick={() => ontheme(s)} data-nowrap>{t(`theme.${s}`)}</button>
        {/each}
      </div>
    </div>
    <div class="sep" role="separator"></div>
    <button class="item" type="button" role="menuitem" onclick={() => (onclose(), onlogout())}>
      <Icon name="log-out" size={18} /><span data-nowrap>{t('nav.logout')}</span>
    </button>
  </div>
{/if}

<style>
  .menu {
    position: absolute;
    top: calc(100% + var(--s1));
    right: var(--s2);
    z-index: 30;
    width: max-content;
    min-width: 240px;
    max-width: calc(100vw - var(--s4));
    padding: var(--s1);
    border: 1px solid var(--border);
    border-radius: 12px;
    background: var(--surface);
    box-shadow: 0 8px 24px rgb(0 0 0 / 0.12);
    animation: drop 120ms ease-out;
    transform-origin: top right;
  }

  .item {
    display: flex;
    align-items: center;
    gap: var(--s3);
    width: 100%;
    min-height: var(--tap);
    padding: 0 var(--s3);
    border: 0;
    border-radius: var(--radius);
    background: none;
    text-align: left;
    white-space: nowrap;
    cursor: pointer;
  }

  .item :global(svg),
  .theme > :global(svg) {
    color: var(--muted);
  }

  .item:hover,
  .item:focus-visible {
    background: var(--bg);
  }

  .sep {
    height: 1px;
    margin: var(--s1) 0;
    background: var(--border);
  }

  .theme {
    display: flex;
    align-items: center;
    gap: var(--s3);
    padding: var(--s1) var(--s1) var(--s1) var(--s3);
  }

  .seg {
    display: flex;
    flex: 1;
    padding: 2px;
    border-radius: var(--radius);
    background: var(--bg);
    border: 1px solid var(--border);
  }

  .seg button {
    flex: 1;
    min-height: 36px;
    padding: 0 var(--s2);
    border: 0;
    border-radius: var(--radius-sm);
    background: none;
    font-size: var(--fs-sm);
    color: var(--muted);
    white-space: nowrap;
    cursor: pointer;
  }

  .seg button.on {
    background: var(--surface);
    color: var(--text);
    font-weight: 600;
    box-shadow: 0 0 0 1px var(--border);
  }

  @keyframes drop {
    from {
      opacity: 0;
      transform: scale(0.97);
    }
  }
</style>
