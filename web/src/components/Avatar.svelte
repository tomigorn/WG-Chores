<script lang="ts">
  import { initials } from '../lib/words';

  let { name, picture = null, size = 32 }: { name: string; picture?: string | null; size?: number } = $props();
  let failed = $state(false);
</script>

{#if picture && !failed}
  <img class="avatar" src={picture} alt="" referrerpolicy="no-referrer" style:width="{size}px" style:height="{size}px" onerror={() => (failed = true)} />
{:else}
  <span class="avatar initials" aria-hidden="true" style:width="{size}px" style:height="{size}px" style:font-size="{Math.round(size * 0.4)}px">{initials(name)}</span>
{/if}

<style>
  .avatar {
    flex: none;
    border-radius: 50%;
    object-fit: cover;
    border: 1px solid var(--border);
  }

  .initials {
    display: grid;
    place-items: center;
    background: var(--surface);
    color: var(--muted);
    font-weight: 650;
    letter-spacing: 0.02em;
    line-height: 1;
    white-space: nowrap;
  }
</style>
