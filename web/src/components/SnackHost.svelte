<script lang="ts">
  import { onMount } from 'svelte';
  import DoneSnackbar, { type Snack } from './DoneSnackbar.svelte';
  import { clearSnack, onSnack } from '../lib/snack';

  let snack: Snack | null = $state(null);

  onMount(() => onSnack((s) => (snack = s)));
</script>

<DoneSnackbar {snack} onexpire={() => snack && clearSnack(snack.id)} />
