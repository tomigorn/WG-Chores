import { registerHandlers, type Build, type Scope } from './sw/core';

// replaced at build time with the precache list and version
const BUILD = JSON.parse('__WGC_BUILD__') as Build;

registerHandlers(globalThis as unknown as Scope, BUILD);
