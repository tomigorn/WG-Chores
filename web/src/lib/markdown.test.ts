import { describe, expect, it } from 'vitest';
import { legalPage, markdownToHtml } from './markdown';

describe('markdown', () => {
  it('headings_lists_and_paragraphs', () => {
    const html = markdownToHtml('# Title\n\n## Sub\nline one\nline two\n\n- a\n- b\n');
    expect(html).toContain('<h1>Title</h1>');
    expect(html).toContain('<h2>Sub</h2>');
    expect(html).toContain('<p>line one line two</p>');
    expect(html).toContain('<ul><li>a</li><li>b</li></ul>');
  });

  it('tables_with_header_and_cells', () => {
    const html = markdownToHtml('| A | B |\n|---|---|\n| x | `y` |\n');
    expect(html).toContain('<table>');
    expect(html).toContain('<th>A</th><th>B</th>');
    expect(html).toContain('<td>x</td><td><code>y</code></td>');
  });

  it('html_is_escaped_and_links_are_safe', () => {
    const html = markdownToHtml('<script>alert(1)</script> **bold** [ok](https://example.com/a?b=1&c=2) [bad](javascript:alert(1)) [doc](account-deletion.md)');
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<a href="https://example.com/a?b=1&amp;c=2">ok</a>');
    expect(html).not.toContain('javascript:');
    expect(html).toContain('<a href="/account-deletion" data-external>doc</a>');
  });

  it('page_uses_app_css_without_inline_code', () => {
    const page = legalPage('# WG Chores: privacy\n\ntext', '/assets/app-1.css');
    expect(page).toContain('<title>WG Chores: privacy</title>');
    expect(page).toContain('<link rel="stylesheet" href="/assets/app-1.css">');
    expect(page).toContain('<a href="/" data-external>');
    expect(page).not.toMatch(/<style|style=|onclick|<script>/);
  });
});
