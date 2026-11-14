<script lang="ts">
  import type { Chore } from '../lib/api';
  import { badgeLabel, badgeText } from '../lib/chores';
  import type { Lang } from '../lib/i18n';

  let { chore, lang }: { chore: Chore; lang: Lang } = $props();
</script>

<span class="badge due-{chore.due_level}" role="img" aria-label={badgeLabel(chore, lang)} data-nowrap>{badgeText(chore, lang)}</span>
