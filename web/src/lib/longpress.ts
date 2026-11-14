export class LongPress {
  private timer: ReturnType<typeof setTimeout> | null = null;
  private x = 0;
  private y = 0;
  fired = false;

  constructor(
    private onLong: () => void,
    private delay = 500,
    private slop = 10,
  ) {}

  down(x: number, y: number): void {
    this.cancel();
    this.fired = false;
    this.x = x;
    this.y = y;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.fired = true;
      this.onLong();
    }, this.delay);
  }

  move(x: number, y: number): void {
    if (this.timer && Math.hypot(x - this.x, y - this.y) > this.slop) this.cancel();
  }

  /** True when the press became a long press, so the following click must be ignored. */
  up(): boolean {
    this.cancel();
    return this.fired;
  }

  cancel(): void {
    if (this.timer) clearTimeout(this.timer);
    this.timer = null;
  }
}

export function longpress(node: HTMLElement, onLong: () => void) {
  const lp = new LongPress(() => {
    navigator.vibrate?.(10);
    onLong();
  });
  let suppressClick = false;
  const down = (e: PointerEvent) => {
    if (e.button !== 0 || !e.isPrimary) return;
    suppressClick = false;
    lp.down(e.clientX, e.clientY);
  };
  const move = (e: PointerEvent) => lp.move(e.clientX, e.clientY);
  const up = () => {
    if (lp.up()) suppressClick = true;
  };
  const cancel = () => lp.cancel();
  const click = (e: MouseEvent) => {
    if (!suppressClick) return;
    suppressClick = false;
    e.preventDefault();
    e.stopPropagation();
  };
  const menu = (e: MouseEvent) => {
    e.preventDefault();
    if (lp.fired) return;
    lp.cancel();
    lp.fired = true;
    suppressClick = true;
    onLong();
  };
  const key = (e: KeyboardEvent) => {
    if (e.key === 'ContextMenu' || (e.key === 'F10' && e.shiftKey)) {
      e.preventDefault();
      onLong();
    }
  };
  const listeners: [string, EventListener, AddEventListenerOptions?][] = [
    ['pointerdown', down as EventListener],
    ['pointermove', move as EventListener, { passive: true }],
    ['pointerup', up],
    ['pointercancel', cancel],
    ['pointerleave', cancel],
    ['click', click as EventListener, { capture: true }],
    ['contextmenu', menu as EventListener],
    ['keydown', key as EventListener],
  ];
  for (const [t, f, o] of listeners) node.addEventListener(t, f, o);
  addEventListener('scroll', cancel, { passive: true, capture: true });
  return {
    destroy() {
      lp.cancel();
      for (const [t, f, o] of listeners) node.removeEventListener(t, f, o);
      removeEventListener('scroll', cancel, { capture: true });
    },
  };
}
