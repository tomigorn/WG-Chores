<script lang="ts">
  import type { Snippet } from 'svelte';

  let { open, onclose, label, children }: { open: boolean; onclose: () => void; label: string; children: Snippet } = $props();
  let dialog: HTMLDialogElement | undefined = $state();

  $effect(() => {
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  });

  // the click after a long press that opened this sheet lands on the backdrop; only a press that started there closes it
  let pressedBackdrop = false;

  function press(e: PointerEvent) {
    pressedBackdrop = e.target === dialog;
  }

  function backdrop(e: MouseEvent) {
    if (e.target === dialog && pressedBackdrop) onclose();
    pressedBackdrop = false;
  }
</script>

<dialog bind:this={dialog} class="sheet" aria-label={label} onclose={onclose} onpointerdown={press} onclick={backdrop}>
  {#if open}
    <div class="body">
      <div class="grab" aria-hidden="true"></div>
      {@render children()}
    </div>
  {/if}
</dialog>

<style>
  .sheet {
    margin: auto auto 0;
    padding: 0;
    width: 100%;
    max-width: 480px;
    max-height: calc(100dvh - var(--s10));
    border: 1px solid var(--border);
    border-bottom: 0;
    border-radius: 16px 16px 0 0;
    background: var(--surface);
    color: var(--text);
    overflow: auto;
    overscroll-behavior: contain;
  }

  .sheet[open] {
    animation: rise 160ms ease-out;
  }

  .sheet::backdrop {
    background: rgb(0 0 0 / 0.4);
  }

  .body {
    container-type: inline-size;
    padding: var(--s2) var(--s4) calc(var(--s4) + env(safe-area-inset-bottom));
  }

  .grab {
    width: 36px;
    height: 4px;
    border-radius: 2px;
    background: var(--border-strong);
    opacity: 0.5;
    margin: 0 auto var(--s3);
  }

  @media (min-width: 640px) {
    .sheet {
      margin: auto;
      border-bottom: 1px solid var(--border);
      border-radius: 12px;
    }

    .grab {
      display: none;
    }

    .body {
      padding: var(--s5) var(--s5) var(--s5);
    }
  }

  @keyframes rise {
    from {
      transform: translateY(16px);
      opacity: 0;
    }
  }
</style>
