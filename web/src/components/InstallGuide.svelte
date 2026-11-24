<script lang="ts">
  import Icon from './Icon.svelte';
  import Sheet from './Sheet.svelte';
  import { t } from '../lib/i18n';

  let { open, onclose }: { open: boolean; onclose: () => void } = $props();
</script>

<Sheet {open} {onclose} label={t('install.guide_title')}>
  <div class="guide">
    <h2 data-copy>{t('install.guide_title')}</h2>
    <ol>
      <li><span class="step"><Icon name="share" size={22} /></span><span data-copy>{t('install.step_share')}</span></li>
      <li><span class="step"><Icon name="square-plus" size={22} /></span><span data-copy>{t('install.step_add')}</span></li>
      <li><span class="step"><Icon name="check" size={22} /></span><span data-copy>{t('install.step_confirm')}</span></li>
    </ol>
    <button class="btn btn-primary btn-block" type="button" onclick={onclose}>{t('install.done')}</button>
  </div>
</Sheet>

<style>
  .guide {
    display: flex;
    flex-direction: column;
    gap: var(--s4);
  }

  .guide h2 {
    font-size: var(--fs-md);
    font-weight: 650;
  }

  ol {
    display: flex;
    flex-direction: column;
    gap: var(--s3);
    margin: 0;
    padding: 0;
    list-style: none;
  }

  li {
    display: flex;
    align-items: center;
    gap: var(--s3);
  }

  .step {
    display: grid;
    flex: none;
    place-items: center;
    width: 44px;
    height: 44px;
    border-radius: var(--radius);
    border: 1px solid var(--border);
    background: var(--bg);
    color: var(--accent);
  }
</style>
