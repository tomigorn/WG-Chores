import { ApiError, OfflineError } from './api';

export type BootFailure = 'login' | 'error.offline' | 'error.generic';

export function bootFailure(e: unknown): BootFailure {
  if (e instanceof OfflineError) return 'error.offline';
  if (e instanceof ApiError && e.status === 401 && e.code === 'unauthenticated') return 'login';
  return 'error.generic';
}
