// @vitest-environment happy-dom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

type Router = typeof import('./router');
let r: Router;
let stop: () => void;

function back(state: unknown, path: string) {
  history.replaceState(state, '', path);
  dispatchEvent(new PopStateEvent('popstate', { state }));
}

beforeEach(async () => {
  vi.resetModules();
  sessionStorage.clear();
  history.replaceState(null, '', '/households/3');
  scrollTo(0, 0);
  r = await import('./router');
  stop = r.startRouter();
});

afterEach(() => {
  stop();
  sessionStorage.clear();
});

describe('router scroll', () => {
  it('back_restores_the_scroll_position_of_the_entry', () => {
    const list = history.state;
    scrollTo(0, 640);
    r.navigate('/chores/7');
    expect(scrollY).toBe(0);
    scrollTo(0, 120);
    back(list, '/households/3');
    expect(scrollY).toBe(120);
    r.restoreScroll();
    expect(scrollY).toBe(640);
  });

  it('restore_happens_once', () => {
    const list = history.state;
    scrollTo(0, 640);
    r.navigate('/chores/7');
    back(list, '/households/3');
    r.restoreScroll();
    scrollTo(0, 50);
    r.restoreScroll();
    expect(scrollY).toBe(50);
  });

  it('a_link_to_a_fresh_view_starts_at_the_top', () => {
    scrollTo(0, 640);
    r.navigate('/chores/7');
    r.navigate('/households/3');
    r.restoreScroll();
    expect(scrollY).toBe(0);
  });

  it('forward_restores_too', () => {
    r.navigate('/chores/7');
    const chore = history.state;
    scrollTo(0, 300);
    r.navigate('/chores/7/edit');
    back(chore, '/chores/7');
    r.restoreScroll();
    expect(scrollY).toBe(300);
  });

  it('positions_survive_a_reload', async () => {
    const list = history.state;
    scrollTo(0, 640);
    r.navigate('/chores/7');
    stop();
    vi.resetModules();
    r = await import('./router');
    stop = r.startRouter();
    back(list, '/households/3');
    r.restoreScroll();
    expect(scrollY).toBe(640);
  });

  it('browser_scroll_restoration_is_manual', () => {
    expect(history.scrollRestoration).toBe('manual');
  });
});

describe('in-app back', () => {
  it('goes_back_in_history_when_the_previous_entry_is_the_target', () => {
    r.navigate('/chores/7');
    const spy = vi.spyOn(history, 'back').mockImplementation(() => {});
    expect(r.backTo('/households/3')).toBe(true);
    expect(spy).toHaveBeenCalled();
  });

  it('opens_the_target_when_the_page_was_opened_directly', () => {
    const spy = vi.spyOn(history, 'back').mockImplementation(() => {});
    expect(r.backTo('/households/3')).toBe(false);
    r.navigate('/chores/7');
    expect(r.backTo('/households/9')).toBe(false);
    expect(spy).not.toHaveBeenCalled();
  });
});
