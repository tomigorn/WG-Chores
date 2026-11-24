<script lang="ts" module>
  export interface Snack {
    id: number;
    text: string;
    strong?: string;
    icon?: 'check';
    duration?: number;
    actions?: { label: string; run: () => void }[];
  }
</script>

<script lang="ts">
  import Icon from './Icon.svelte';

  let { snack, onexpire, duration = 6000 }: { snack: Snack | null; onexpire: () => void; duration?: number } = $props();
  let pausedFor: number | null = $state(null);
  const paused = $derived(snack !== null && pausedFor === snack.id);
  const pause = (on: boolean) => (pausedFor = on && snack ? snack.id : null);

  let height = $state(0);

  // the list keeps room below its end so the bar never covers the last controls
  $effect(() => {
    const h = snack ? height : 0;
    document.documentElement.style.setProperty('--snack-h', `${h}px`);
  });

  $effect(() => {
    if (!snack || paused) return;
    const timer = setTimeout(onexpire, snack.duration ?? duration);
    return () => clearTimeout(timer);
  });
</script>

<div class="region" role="status" aria-live="polite">
  {#if snack}
    {#key snack.id}
      <div class="snack" bind:offsetHeight={height} role="group" onfocusin={() => pause(true)} onfocusout={() => pause(false)} onpointerenter={() => pause(true)} onpointerleave={() => pause(false)}>
        {#if snack.icon}<Icon name={snack.icon} size={18} />{/if}
        <span class="text">
          {#if snack.strong}<span class="strong clamp" data-clamp="2">{snack.strong}</span>{/if}<span class="rest" data-nowrap={snack.strong ? '' : undefined}>{snack.text}</span>
        </span>
        {#if snack.actions?.length}
          <span class="acts">
            {#each snack.actions as a (a.label)}
              <button class="act" type="button" onclick={a.run} data-nowrap>{a.label}</button>
            {/each}
          </span>
        {/if}
      </div>
    {/key}
  {/if}
</div>

<style>
  .region {
    container-type: inline-size;
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 20;
    display: flex;
    justify-content: center;
    padding: 0 var(--s3) calc(var(--s3) + env(safe-area-inset-bottom));
    pointer-events: none;
  }

  .snack {
    pointer-events: auto;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    column-gap: var(--s2);
    width: 100%;
    max-width: 560px;
    min-height: 52px;
    padding: var(--s1) var(--s1) var(--s1) var(--s4);
    border-radius: 12px;
    background: light-dark(#1f2023, #ececef);
    color: light-dark(#f3f3f5, #18181b);
    box-shadow: 0 4px 16px rgb(0 0 0 / 0.18);
    animation: up 140ms ease-out;
  }

  .text {
    display: flex;
    min-width: 0;
    flex: 1 1 14em;
    gap: 0.3em;
    padding: var(--s2) 0;
    font-size: var(--fs-sm);
  }

  .strong {
    font-weight: 600;
    min-width: 2.5em;
  }

  .rest {
    flex: none;
    white-space: nowrap;
  }

  .text:not(:has(.strong)) .rest {
    flex: 1;
    white-space: normal;
  }

  @container (max-width: 21em) {
    .text {
      flex-wrap: wrap;
    }

    .strong {
      max-width: 100%;
    }
  }

  .acts {
    display: flex;
    margin-left: auto;
  }

  .act {
    flex: none;
    min-height: var(--tap);
    padding: 0 var(--s2);
    border: 0;
    border-radius: var(--radius);
    background: none;
    color: light-dark(#a9beff, #2a55c5);
    font-size: var(--fs-sm);
    font-weight: 650;
    white-space: nowrap;
    cursor: pointer;
  }

  .act:hover {
    background: light-dark(rgb(255 255 255 / 0.08), rgb(0 0 0 / 0.06));
  }

  @keyframes up {
    from {
      transform: translateY(12px);
      opacity: 0;
    }
  }
</style>
