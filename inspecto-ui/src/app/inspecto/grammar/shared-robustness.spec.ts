import { describe, expect, it } from 'vitest';
import { parsingAttributesFor, type ParsingFrontend } from './parsing-attributes';

/**
 * The six shared `read_csv` error-handling knobs reach every LINE-READER frontend, not just delimited.
 *
 * <p>They were offered on the Dialect/Robustness tab of `delimited` only, although
 * `DuckDbCsvIngester.errorOptions` emits them for the fixed-width and text-regex lanes too — so an
 * operator parsing a fixed-width feed had no way to author `ignore_errors` / `store_rejects` at all.
 *
 * <p>🔴 The load-bearing detail these pin: the keys stay `delimited__*` on EVERY frontend, because
 * `PipelineConfigParser.mergeParsing` FLATTENS the `delimited:` block into the shared csv_settings
 * while copying `fixedwidth:` / `text_regex:` through as NESTED sub-blocks. A mirrored
 * `fixedwidth__ignore_errors` would be written, validated and saved — and never read by the engine.
 * If someone "tidies" these into per-frontend keys, these tests are what fails.
 */
describe('shared read_csv robustness knobs', () => {
    const SHARED = [
        'delimited__ignore_errors',
        'delimited__null_padding',
        'delimited__store_rejects',
        'delimited__rejects_table',
        'delimited__rejects_scan',
        'delimited__rejects_limit',
    ];

    const LINE_READERS: ParsingFrontend[] = ['delimited', 'fixedwidth', 'text_regex'];

    it.each(LINE_READERS)('%s offers all six, under the shared delimited__ keys', (frontend) => {
        const keys = parsingAttributesFor(frontend).map((s) => s.key);
        for (const k of SHARED) expect(keys, `${frontend} is missing ${k}`).toContain(k);
    });

    it.each(LINE_READERS)('%s declares each knob exactly once', (frontend) => {
        const keys = parsingAttributesFor(frontend).map((s) => s.key);
        for (const k of SHARED) {
            expect(
                keys.filter((x) => x === k),
                `${frontend} duplicates ${k}`,
            ).toHaveLength(1);
        }
    });

    it('never mirrors them under a per-frontend root — that key would be DEAD CONFIG', () => {
        for (const frontend of LINE_READERS) {
            const keys = parsingAttributesFor(frontend).map((s) => s.key);
            for (const bad of [
                'fixedwidth__ignore_errors',
                'text_regex__ignore_errors',
                'fixedwidth__store_rejects',
                'text_regex__store_rejects',
            ]) {
                expect(
                    keys,
                    `${frontend} mirrors ${bad}: mergeParsing keeps that root NESTED, so the ` +
                        `engine never reads it`,
                ).not.toContain(bad);
            }
        }
    });

    it('states the padding default the operator will actually get, per frontend', () => {
        const help = (f: ParsingFrontend) =>
            parsingAttributesFor(f).find((s) => s.key === 'delimited__null_padding')?.help ?? '';
        // errorOptions(cfg, nullPaddingDefault): false for delimited, true for every line-reader path.
        expect(help('delimited')).toContain('Off for delimited by default');
        expect(help('fixedwidth')).toContain('ON by default');
        expect(help('text_regex')).toContain('ON by default');
    });
});
