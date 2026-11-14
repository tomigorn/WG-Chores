// @vitest-environment happy-dom
import { createRawSnippet, flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { dialogs } from '../screens/testkit';
import Sheet from './Sheet.svelte';

dialogs();

afterEach(() => {
  document.body.innerHTML = '';
});

function open() {
  const onclose = vi.fn();
  const children = createRawSnippet(() => ({ render: () => '<p>content</p>' }));
  const app = mount(Sheet, { target: document.body, props: { open: true, onclose, label: 'Sheet', children } });
  flushSync();
  return { app, onclose, dialog: document.querySelector('dialog')! };
}

describe('Sheet', () => {
  it('backdrop_press_and_click_closes', () => {
    const { app, onclose, dialog } = open();
    dialog.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    dialog.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(onclose).toHaveBeenCalledTimes(1);
    unmount(app);
  });

  it('click_after_a_long_press_elsewhere_keeps_it_open', () => {
    const { app, onclose, dialog } = open();
    dialog.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(onclose).not.toHaveBeenCalled();
    unmount(app);
  });

  it('press_inside_released_on_backdrop_keeps_it_open', () => {
    const { app, onclose, dialog } = open();
    dialog.querySelector('p')!.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    dialog.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(onclose).not.toHaveBeenCalled();
    unmount(app);
  });
});
