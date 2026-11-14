// @vitest-environment happy-dom
import { flushSync, mount, unmount } from 'svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import Menu from './Menu.svelte';

afterEach(() => {
  document.body.innerHTML = '';
});

function open() {
  const onclose = vi.fn();
  const ontheme = vi.fn();
  const app = mount(Menu, { target: document.body, props: { open: true, onclose, theme: 'dark', ontheme, onnavigate: () => {}, onlogout: () => {} } });
  flushSync();
  return { app, onclose, ontheme, menu: document.querySelector('[role=menu]')! };
}

describe('Menu', () => {
  it('menu_roles_are_valid', () => {
    const { app, menu } = open();
    const owned = [...menu.querySelectorAll('[role]')].filter((e) => e !== menu).map((e) => e.getAttribute('role'));
    expect(owned.every((r) => ['menuitem', 'menuitemradio', 'separator', 'none'].includes(r!))).toBe(true);
    const radios = [...menu.querySelectorAll('[role=menuitemradio]')];
    expect(radios.map((r) => r.getAttribute('aria-label'))).toEqual(['Theme: System', 'Theme: Light', 'Theme: Dark']);
    expect(radios.map((r) => r.getAttribute('aria-checked'))).toEqual(['false', 'false', 'true']);
    unmount(app);
  });

  it('tab_and_escape_close_the_menu', () => {
    const { app, onclose, menu } = open();
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'Tab', bubbles: true }));
    expect(onclose).toHaveBeenCalledTimes(1);
    menu.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
    expect(onclose).toHaveBeenCalledTimes(2);
    unmount(app);
  });
});
