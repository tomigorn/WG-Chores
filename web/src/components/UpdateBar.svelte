<script lang="ts">
  import Icon from './Icon.svelte';
  import { t } from '../lib/i18n';

  let { onreload }: { onreload: () => void } = $props();
  let busy = $state(false);
</script>

<div class="update" role="status">
  <Icon name="refresh-cw" size={16} />
  <span class="text" data-nowrap>{t('update.available')}</span>
  <button
    class="btn-link"
    type="button"
    disabled={busy}
    onclick={() => {
      busy = true;
      onreload();
    }}>{t('update.reload')}</button
  >
</div>

<style>
  .update {
    position: fixed;
    left: 50%;
    bottom: calc(var(--s4) + env(safe-area-inset-bottom));
    z-index: 20;
    transform: translateX(-50%);
    display: flex;
    align-items: center;
    gap: var(--s2);
    max-width: calc(100vw - 2 * var(--s4));
    padding: 0 var(--s2) 0 var(--s4);
    border: 1px solid var(--border);
    border-radius: 999px;
    background: var(--surface);
    color: var(--muted);
    font-size: var(--fs-sm);
    box-shadow: 0 1px 3px rgb(0 0 0 / 0.08);
  }

  .text {
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .btn-link {
    padding: 0 var(--s2);
    white-space: nowrap;
    font-size: var(--fs-sm);
  }
</style>
