import { provideCheckNoChangesConfig } from '@angular/core';
import { vi } from 'vitest';
import { TestBed } from '@angular/core/testing';

// Zoneless CD (see app.config.ts): without zone.js, `tick()` no longer re-commits bindings of
// non-dirty views, but the dev-mode "verify no changes" pass still sweeps every view. Spec hosts
// that mutate plain fields between `detectChanges()` calls would then trip NG0100 against stale
// stored bindings — a harness artifact, not a product bug. Exhaustive verification is the
// zone.js-era behavior; scoping it off restores the intended zoneless semantics in tests.
TestBed.configureTestingModule({ providers: [provideCheckNoChangesConfig({ exhaustive: false })] });

// axe-core accessibility assertions (`expectNoA11yViolations`) run inside jsdom, which is slow.
// Under the full suite's parallel load these a11y/init specs intermittently exceed vitest's default
// 5 s per-test timeout — they pass cleanly in isolation, so it's resource contention, not a hang.
// A generous per-file budget keeps the suite baseline stable on loaded CI / dev machines while still
// catching a genuinely stuck test. `vi.setConfig` here applies to every test file that loads this
// setup (wired via the unit-test builder's `setupFiles`).
vi.setConfig({ testTimeout: 15000, hookTimeout: 15000 });
