import { describe, expect, it, vi } from 'vitest';
import { watchUpdates, type ContainerLike } from './update';

class Target {
  private l = new Map<string, Set<(e: { data?: unknown }) => void>>();
  addEventListener(t: string, cb: (e: { data?: unknown }) => void) {
    if (!this.l.has(t)) this.l.set(t, new Set());
    this.l.get(t)!.add(cb);
  }
  emit(t: string, e: { data?: unknown } = {}) {
    for (const cb of this.l.get(t) ?? []) cb(e);
  }
}

class Worker extends Target {
  state = 'installing';
  postMessage = vi.fn();
}

function setup(controlled: boolean, waiting: Worker | null = null) {
  const reg = Object.assign(new Target(), { waiting, installing: null as Worker | null, update: vi.fn(async () => {}) });
  const container = Object.assign(new Target(), {
    controller: controlled ? {} : null,
    register: vi.fn(async () => reg),
  }) satisfies ContainerLike;
  const reload = vi.fn();
  const onUpdate = vi.fn();
  const navigate = vi.fn();
  return { reg, container, reload, onUpdate, navigate, start: () => watchUpdates(container, { reload, onUpdate, navigate }) };
}

describe('service worker updates', () => {
  it('update_offered_only_for_a_waiting_worker_over_a_controller', async () => {
    const first = setup(false);
    await first.start();
    const w = new Worker();
    first.reg.installing = w;
    first.reg.emit('updatefound');
    w.state = 'installed';
    w.emit('statechange');
    expect(first.onUpdate).not.toHaveBeenCalled();

    const later = setup(true);
    await later.start();
    const w2 = new Worker();
    later.reg.installing = w2;
    later.reg.emit('updatefound');
    w2.state = 'installed';
    w2.emit('statechange');
    expect(later.onUpdate).toHaveBeenCalledTimes(1);
  });

  it('reload_only_after_the_user_asks', async () => {
    const waiting = new Worker();
    const s = setup(true, waiting);
    await s.start();
    expect(s.onUpdate).toHaveBeenCalledTimes(1);
    s.container.emit('controllerchange');
    expect(s.reload).not.toHaveBeenCalled();
    const apply = s.onUpdate.mock.calls[0]![0] as () => void;
    apply();
    expect(waiting.postMessage).toHaveBeenCalledWith({ type: 'skip-waiting' });
    s.container.emit('controllerchange');
    expect(s.reload).toHaveBeenCalledTimes(1);
  });

  it('navigate_message_from_notification_click', async () => {
    const s = setup(true);
    await s.start();
    s.container.emit('message', { data: { type: 'navigate', url: '/households/3' } });
    s.container.emit('message', { data: { type: 'navigate', url: 'https://evil.example.org/' } });
    expect(s.navigate).toHaveBeenCalledTimes(1);
    expect(s.navigate).toHaveBeenCalledWith('/households/3');
  });
});
