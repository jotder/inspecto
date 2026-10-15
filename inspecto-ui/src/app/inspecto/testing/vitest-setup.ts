import { TestBed } from '@angular/core/testing';
import { beforeEach, vi } from 'vitest';
import { environment } from '../../../environments/environment';
import { setAppEnvironment } from '../api/app-environment';

// Relative paths: a setup file bypasses the tsconfig `paths` (plain vite resolution).
// D-5 step 2: `api/` has no import of `environments/*`; every spec file gets the same object the host app.config provides.
setAppEnvironment(environment);

// axe-core accessibility assertions (`expectNoA11yViolations`) run inside jsdom, which is slow.
// Under the full suite's parallel load these a11y/init specs intermittently exceed vitest's default
// 5 s per-test timeout — they pass cleanly in isolation, so it's resource contention, not a hang.
// A generous per-file budget keeps the suite baseline stable on loaded CI / dev machines while still
// catching a genuinely stuck test. `vi.setConfig` here applies to every test file that loads this
// setup (wired via the unit-test builder's `setupFiles`).
vi.setConfig({ testTimeout: 15000, hookTimeout: 15000 });

// The unit-test builder runs Vitest NON-isolated (one TestBed shared by every spec file in a worker). A test that leaves
// an async continuation behind (a promise or timer that injects or creates a component after its own afterEach reset)
// instantiates the shared TestBed again, and the NEXT file's first `configureTestingModule` then dies with "Cannot
// configure the test module when the test module has already been instantiated" - a failure that moves between files
// with the worker's file order (seen on link-analysis-query-panel and la-host.providers, 2026-10-15). Resetting before
// every test makes each test start from a clean TestBed whatever ran before it in the worker.
beforeEach(() => TestBed.resetTestingModule());
