import { describe, expect, it } from 'vitest';
import { ParserPreview } from 'app/inspecto/api';
import { sampleResolutions } from './sample-resolution';

const table = (resolved?: Record<string, string>): ParserPreview => ({
    kind: 'table',
    columns: [],
    rows: [],
    rowCount: 0,
    rejectedRows: 0,
    resolved,
});

describe('sampleResolutions', () => {
    it('maps the served dialect onto the delimited spec keys, formatted for a person', () => {
        expect(
            sampleResolutions(
                table({
                    delimiter: '\t',
                    quote: '',
                    escape: '"',
                    comment: '',
                    has_header: 'true',
                    skip_header_lines: '2',
                    date_format: '%d/%m/%Y',
                    timestamp_format: '',
                }),
            ),
        ).toEqual({
            delimited__delimiter: 'tab',
            delimited__quote: 'none',
            delimited__escape: '"',
            delimited__comment: 'none',
            delimited__has_header: 'yes',
            delimited__skip_header_lines: '2',
            delimited__date_formats: '%d/%m/%Y',
        });
    });

    it('says nothing for an old server, a tree preview, no preview, or an option it does not know', () => {
        expect(sampleResolutions(table())).toEqual({});
        expect(sampleResolutions({ kind: 'tree', recordCount: 0, nodes: [] })).toEqual({});
        expect(sampleResolutions(null)).toEqual({});
        expect(sampleResolutions(table({ columns: '[...]', has_header: 'false' }))).toEqual({
            delimited__has_header: 'no',
        });
    });
});
