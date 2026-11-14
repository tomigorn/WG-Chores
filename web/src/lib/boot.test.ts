import { describe, expect, it } from 'vitest';
import { ApiError, OfflineError } from './api';
import { bootFailure } from './boot';

describe('boot', () => {
  it('only_unauthenticated_leads_to_login', () => {
    expect(bootFailure(new ApiError(401, 'unauthenticated', 'x'))).toBe('login');
    expect(bootFailure(new ApiError(500, 'internal', 'x'))).toBe('error.generic');
    expect(bootFailure(new ApiError(200, 'invalid_response', 'x'))).toBe('error.generic');
    expect(bootFailure(new OfflineError())).toBe('error.offline');
    expect(bootFailure(new Error('boom'))).toBe('error.generic');
  });
});
