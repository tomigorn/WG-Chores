const esc = (s: string) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

const DOC_LINKS: Record<string, string> = { 'privacy.md': '/privacy', 'account-deletion.md': '/account-deletion' };

function href(url: string): string | null {
  if (DOC_LINKS[url]) return DOC_LINKS[url];
  return /^(https?:\/\/|mailto:)/.test(url) ? url : null;
}

function inline(s: string): string {
  const codes: string[] = [];
  let out = esc(s).replace(/`([^`]+)`/g, (_, c: string) => `\0${codes.push(`<code>${c}</code>`) - 1}\0`);
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_, text: string, url: string) => {
    const h = href(url.replace(/&amp;/g, '&'));
    if (h === null) return text;
    return `<a href="${esc(h)}"${h.startsWith('/') ? ' data-external' : ''}>${text}</a>`;
  });
  return out.replace(/\0(\d+)\0/g, (_, i: string) => codes[Number(i)]!);
}

const cells = (line: string) => line.replace(/^\s*\|/, '').replace(/\|\s*$/, '').split('|').map((c) => c.trim());

/** Converts the small markdown subset used by the docs: headings, paragraphs, bullet lists, tables, code, bold, links. */
export function markdownToHtml(md: string): string {
  const lines = md.replace(/\r\n?/g, '\n').split('\n');
  const out: string[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i]!;
    const h = /^(#{1,3})\s+(.*)$/.exec(line);
    if (!line.trim()) i++;
    else if (h) {
      out.push(`<h${h[1]!.length}>${inline(h[2]!)}</h${h[1]!.length}>`);
      i++;
    } else if (/^\s*\|/.test(line)) {
      const rows: string[] = [];
      while (i < lines.length && /^\s*\|/.test(lines[i]!)) rows.push(lines[i++]!);
      const body = rows.filter((r) => !/^[\s|:-]+$/.test(r)).map(cells);
      const [head, ...rest] = body;
      const tr = (c: string[], tag: string) => `<tr>${c.map((x) => `<${tag}>${inline(x)}</${tag}>`).join('')}</tr>`;
      out.push(`<table><thead>${head ? tr(head, 'th') : ''}</thead><tbody>${rest.map((r) => tr(r, 'td')).join('')}</tbody></table>`);
    } else if (/^\s*[-*]\s+/.test(line)) {
      const items: string[] = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i]!)) items.push(`<li>${inline(lines[i++]!.replace(/^\s*[-*]\s+/, ''))}</li>`);
      out.push(`<ul>${items.join('')}</ul>`);
    } else {
      const para: string[] = [];
      while (i < lines.length && lines[i]!.trim() && !/^(#{1,3}\s|\s*\||\s*[-*]\s)/.test(lines[i]!)) para.push(lines[i++]!.trim());
      out.push(`<p>${inline(para.join(' '))}</p>`);
    }
  }
  return out.join('\n');
}

/** A full static page for a doc, styled by the app's stylesheet (no inline script or style). */
export function legalPage(md: string, css: string): string {
  const title = /^#\s+(.*)$/m.exec(md)?.[1] ?? 'WG Chores';
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>${esc(title.replace(/`/g, ''))}</title>
<link rel="icon" type="image/png" sizes="192x192" href="/icons/icon-192.png">
<link rel="stylesheet" href="${esc(css)}">
<script src="/theme-init.js"></script>
</head>
<body>
<main class="screen legal">
<p><a href="/" data-external>&larr; WG Chores</a></p>
${markdownToHtml(md)}
</main>
</body>
</html>
`;
}
