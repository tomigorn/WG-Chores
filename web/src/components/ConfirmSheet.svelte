<script lang="ts">
  import type { Snippet } from 'svelte';
  import Sheet from './Sheet.svelte';

  let {
    open,
    title,
    text,
    confirm,
    cancel,
    danger = false,
    disabled = false,
    busy = false,
    onconfirm,
    onclose,
    children,
  }: {
    open: boolean;
    title: string;
    text: string;
    confirm: string;
    cancel: string;
    danger?: boolean;
    disabled?: boolean;
    busy?: boolean;
    onconfirm: () => void;
    onclose: () => void;
    children?: Snippet;
  } = $props();

  function submit(e: SubmitEvent) {
    e.preventDefault();
    if (!disabled && !busy) onconfirm();
  }
</script>

<Sheet {open} {onclose} label={title}>
  <form class="confirm" onsubmit={submit}>
    <h2>{title}</h2>
    <p>{text}</p>
    {#if children}{@render children()}{/if}
    <button class="btn btn-block" class:btn-primary={!danger} class:btn-danger={danger} type="submit" disabled={disabled || busy}>{confirm}</button>
    <button class="btn btn-block" type="button" onclick={onclose}>{cancel}</button>
  </form>
</Sheet>

<style>
  .confirm {
    display: flex;
    flex-direction: column;
    gap: var(--s3);
  }

  h2 {
    font-size: var(--fs-md);
    font-weight: 650;
  }

  p {
    color: var(--muted);
    margin-bottom: var(--s2);
  }
</style>
