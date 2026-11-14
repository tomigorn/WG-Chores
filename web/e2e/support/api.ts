import type { BrowserContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export const PASSWORD = 'correct horse battery'; // leak-check: ok
export const COOKIE = '__Host-wgc_session';

export interface Household {
  id: number;
  name: string;
}

export interface Chore {
  id: number;
  name: string;
  category: string | null;
  days_since: number | null;
}

export interface Completion {
  id: string;
  done_on: string;
  note: string | null;
}

let seq = 0;
/** A username unique within the run (3–32 chars, a–z 0–9 . _ -). */
export function uniqueName(prefix = 'user'): string {
  seq += 1;
  return `${prefix}-${process.pid % 10000}-${seq}-${Math.random().toString(36).slice(2, 6)}`.slice(0, 32);
}

export function isoDay(daysAgo = 0): string {
  return new Date(Date.now() - daysAgo * 86_400_000).toISOString().slice(0, 10);
}

/** Seeds and checks data through the real API, as one signed-in user. */
export class Api {
  session: string | null = null;
  username = '';

  constructor(readonly base: string) {}

  async req<T = unknown>(method: string, path: string, body?: unknown): Promise<{ status: number; json: T }> {
    const headers: Record<string, string> = { 'X-WGC': '1', accept: 'application/json', origin: this.base };
    if (body !== undefined) headers['content-type'] = 'application/json';
    if (this.session) headers.cookie = `${COOKIE}=${this.session}`;
    const r = await fetch(this.base + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
    const m = /__Host-wgc_session=([^;]*)/.exec(r.headers.get('set-cookie') ?? '');
    if (m) this.session = m[1] || null;
    const text = await r.text();
    return { status: r.status, json: (text ? JSON.parse(text) : null) as T };
  }

  async ok<T = unknown>(method: string, path: string, body?: unknown): Promise<T> {
    const r = await this.req<T>(method, path, body);
    if (r.status >= 300) throw new Error(`${method} ${path}: ${r.status} ${JSON.stringify(r.json)}`);
    return r.json;
  }

  static async register(base: string, displayName = 'Anna', username = uniqueName(), extra: Record<string, string> = {}): Promise<Api> {
    const a = new Api(base);
    a.username = username;
    await a.ok('POST', '/api/v1/auth/register', { username, password: PASSWORD, display_name: displayName, ...extra }); // leak-check: ok
    return a;
  }

  static async login(base: string, username: string): Promise<Api> {
    const a = new Api(base);
    a.username = username;
    await a.ok('POST', '/api/v1/auth/login', { username, password: PASSWORD }); // leak-check: ok
    return a;
  }

  household(name: string): Promise<Household> {
    return this.ok('POST', '/api/v1/households', { name });
  }

  chore(hid: number, name: string, category?: string, interval?: string): Promise<Chore> {
    return this.ok('POST', `/api/v1/households/${hid}/chores`, { name, category, interval });
  }

  done(choreId: number, doneOn = isoDay(0), note?: string): Promise<unknown> {
    return this.ok('POST', `/api/v1/chores/${choreId}/completions`, { id: randomUUID(), done_on: doneOn, note });
  }

  async completions(choreId: number): Promise<Completion[]> {
    return (await this.ok<{ items: Completion[] }>('GET', `/api/v1/chores/${choreId}/completions`)).items;
  }

  chores(hid: number): Promise<Chore[]> {
    return this.ok('GET', `/api/v1/households/${hid}/chores`);
  }

  addMember(hid: number, username: string): Promise<unknown> {
    return this.ok('POST', `/api/v1/households/${hid}/members`, { username });
  }

  invite(hid: number): Promise<{ id: number; url: string }> {
    return this.ok('POST', `/api/v1/households/${hid}/invites`);
  }

  /** Hands this API session to a browser context, so the page starts signed in. */
  async signInContext(context: BrowserContext): Promise<void> {
    if (!this.session) throw new Error('not signed in');
    await context.addCookies([{ name: COOKIE, value: this.session, url: this.base.replace(/^http:/, 'https:') + '/', secure: true, httpOnly: true, sameSite: 'Lax' }]);
  }
}
