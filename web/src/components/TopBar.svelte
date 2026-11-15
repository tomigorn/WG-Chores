<script lang="ts">
  import Avatar from './Avatar.svelte';
  import Icon from './Icon.svelte';
  import Menu from './Menu.svelte';
  import type { Me } from '../lib/api';
  import { t } from '../lib/i18n';
  import type { ThemeSetting } from '../lib/theme';

  let {
    title,
    me,
    pending,
    offline,
    theme,
    onswitch,
    onstatus,
    ontheme,
    onnavigate,
    onlogout,
    householdId = null,
  }: {
    title: string;
    me: Me;
    pending: number;
    offline: boolean;
    theme: ThemeSetting;
    onswitch: () => void;
    onstatus: (text: string) => void;
    ontheme: (s: ThemeSetting) => void;
    onnavigate: (path: string) => void;
    onlogout: () => void;
    householdId?: number | null;
  } = $props();

  let menuOpen = $state(false);
  let height = $state(0);

  // sticky category headers sit right below the bar, which grows when the name takes two lines
  $effect(() => {
    if (height) document.documentElement.style.setProperty('--topbar-h', `${height}px`);
  });
</script>

<header class="bar" bind:offsetHeight={height}>
  <button class="hh" type="button" onclick={onswitch} aria-haspopup="dialog" aria-label="{title}, {t('menu.switch')}">
    <span class="name clamp" data-clamp="2">{title}</span>
    <Icon name="chevron-down" size={18} />
  </button>
  {#if pending > 0}
    <button class="chip" type="button" aria-label={t('sync.waiting_label', { n: pending })} onclick={() => onstatus(t('sync.waiting', { n: pending }))}>
      <span class="pill"><Icon name="hourglass" size={14} /><span data-nowrap>{pending}</span><span class="long" data-nowrap>{t('sync.short')}</span></span>
    </button>
  {:else if offline}
    <button class="chip" type="button" aria-label={t('list.offline_note')} onclick={() => onstatus(t('list.offline_note'))}>
      <span class="pill"><Icon name="wifi-off" size={14} /><span class="long" data-nowrap>{t('list.offline')}</span></span>
    </button>
  {/if}
  <button class="icon-btn" type="button" data-menu-button aria-label={t('menu.open')} aria-haspopup="menu" aria-expanded={menuOpen} onclick={() => (menuOpen = !menuOpen)}>
    <Icon name="menu" size={22} />
  </button>
  <a class="icon-btn avatar-link" href="/account" aria-label={t('nav.account')}>
    <Avatar name={me.display_name} picture={me.picture_url} size={32} />
  </a>
  <Menu open={menuOpen} onclose={() => (menuOpen = false)} {theme} {ontheme} {onnavigate} {onlogout} {householdId} />
</header>

<style>
  .bar {
    container-type: inline-size;
    position: sticky;
    top: env(safe-area-inset-top, 0);
    z-index: 10;
    display: flex;
    align-items: center;
    gap: var(--s1);
    min-height: var(--bar-h);
    padding: 0 var(--s2) 0 var(--s1);
    background: var(--bg);
    border-bottom: 1px solid var(--border);
  }

  .hh {
    display: flex;
    align-items: center;
    gap: var(--s1);
    min-width: 0;
    min-height: var(--tap);
    margin-right: auto;
    padding: 0 var(--s2) 0 var(--s3);
    border: 0;
    border-radius: var(--radius);
    background: none;
    font-size: var(--fs-lg);
    font-weight: 680;
    letter-spacing: -0.01em;
    text-align: left;
    cursor: pointer;
  }


  .hh:hover {
    background: var(--surface);
  }

  .hh :global(svg) {
    color: var(--muted);
  }

  .name {
    line-height: 1.25;
  }

  .chip {
    display: grid;
    flex: none;
    place-items: center;
    min-width: var(--tap);
    height: var(--tap);
    padding: 0;
    border: 0;
    background: none;
    cursor: pointer;
  }

  .pill {
    display: inline-flex;
    align-items: center;
    gap: var(--s1);
    min-height: 28px;
    padding: 0 var(--s2);
    border-radius: 999px;
    border: 1px solid var(--border);
    background: var(--surface);
    color: var(--muted);
    font-size: var(--fs-xs);
    font-weight: 600;
    font-variant-numeric: tabular-nums;
    line-height: 1;
    white-space: nowrap;
  }

  .chip .long {
    display: none;
  }

  @media (min-width: 560px) {
    .chip .long {
      display: inline;
    }
  }

  .icon-btn {
    display: grid;
    flex: none;
    place-items: center;
    width: var(--tap);
    height: var(--tap);
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: none;
    color: var(--text);
    cursor: pointer;
  }

  .icon-btn:hover {
    background: var(--surface);
  }

  .avatar-link {
    text-decoration: none;
  }

  /* narrow: the household name gets the room; the account stays one tap away in the menu, the name itself still opens the switcher */
  @container (max-width: 21em) {
    .hh {
      padding: 0 var(--s1) 0 var(--s2);
      font-size: var(--fs-md);
    }

    .avatar-link,
    .hh :global(svg) {
      display: none;
    }
  }
</style>
