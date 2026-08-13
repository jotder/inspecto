import { describe, expect, it } from 'vitest';
import type { ParserDef } from '../../api/parsers.service';
import type { ParserTablePreview, ParserTreePreview } from '../../api/components.service';
import { MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { parsersHandler } from './parsers.handler';

/**
 * Pins the mock's STRICTNESS to the server contract (ParserRoutes + Parsers + XmlParserPlugin):
 * same catalog ids/order/flags, same refusal statuses and message shapes. A mock more lenient
 * than the server converts hard failures into passing rehearsals — never loosen these.
 */

const handler = parsersHandler();
const store = new MockStore({ get: () => null, set: () => undefined, remove: () => undefined });

function req(method: string, url: string, body?: unknown): MockRequest {
    return { method, url, body, params: {}, space: 'default' };
}

function send(method: string, url: string, body?: unknown) {
    return handler(req(method, url, body), store);
}

describe('parsersHandler', () => {
    it('serves the catalog: built-ins first, then the two hierarchical plugins', () => {
        const res = send('GET', '/api/parsers')!;
        const list = res.body as ParserDef[];
        expect(list.map((p) => p.id)).toEqual(['delimited', 'fixedwidth', 'json', 'text_regex', 'xml', 'asn1']);
        // Both plugins are tree-shaped; they differ on whether they can load to Tables, and that
        // difference is exactly what a guided Save gates on.
        const xml = list[4];
        expect(xml.hierarchical).toBe(true);
        expect(xml.ingestable).toBe(false); // preview-only until the flatten configuration
        expect(xml.ingesterClass).toBeUndefined();
        const asn1 = list[5];
        expect(asn1.hierarchical).toBe(true);
        expect(asn1.ingestable).toBe(true);
        expect(asn1.ingesterClass).toBe('com.gamma.ingester.Asn1RecordIngester');
        for (const p of list) expect(p.grammarSchema.length).toBeGreaterThan(0);
    });

    it('previews a delimited sample as a table', () => {
        const res = send('POST', '/api/parsers/delimited/preview', {
            grammar: { delimited: { has_header: true } },
            sample_text: 'id,qty\n1001,3\n',
        })!;
        expect(res.status).toBe(200);
        const t = res.body as ParserTablePreview;
        expect(t.kind).toBe('table');
        expect(t.columns).toEqual(['id', 'qty']);
        expect(t.rowCount).toBe(1);
    });

    it('previews NDJSON with rejected invalid lines and stringified nested values (server parity)', () => {
        const res = send('POST', '/api/parsers/json/preview', {
            grammar: {},
            sample_text: '{"a": 1, "b": {"c": 2}}\n{oops\n',
        })!;
        const t = res.body as ParserTablePreview;
        expect(t.columns).toEqual(['a', 'b']);
        expect(t.rowCount).toBe(1);
        expect(t.rejectedRows).toBe(1);
        expect(t.rows[0]['b']).toBe('{"c":2}');
    });

    it('previews XML as a record tree with @attr leaves and counts all matches', () => {
        const res = send('POST', '/api/parsers/xml/preview', {
            grammar: { xml: { max_records: 1 } },
            sample_text: '<orders><order id="1"><amount>42.5</amount></order><order id="2"/></orders>',
        })!;
        const t = res.body as ParserTreePreview;
        expect(t.kind).toBe('tree');
        expect(t.recordCount).toBe(2);
        expect(t.nodes).toHaveLength(1); // capped by max_records
        expect(t.nodes[0].label).toBe('order');
        expect(t.nodes[0].children![0]).toEqual({ label: '@id', type: 'attr', value: '1' });
        expect(t.nodes[0].children![1].value).toBe('42.5');
    });

    it('refuses like the server: 404 unknown id, 400 missing/oversized sample, 422 caller errors', () => {
        expect(send('POST', '/api/parsers/made_up_format/preview', { sample_text: 'x' })!.status).toBe(404);
        // asn1 IS in the catalog now, so it is not a 404 — the mock refuses it 422 instead, since
        // decoding binary BER in a mock would be a second ASN.1 implementation (stricter, never
        // more lenient, than the server).
        const asn1 = send('POST', '/api/parsers/asn1/preview', { sample_text: 'x' })!;
        expect(asn1.status).toBe(422);
        expect(String((asn1.body as { error: string }).error)).toContain('not available in mock mode');
        expect(send('POST', '/api/parsers/delimited/preview', {})!.status).toBe(400);
        expect(send('POST', '/api/parsers/delimited/preview', { sample_text: 'x'.repeat(1_000_001) })!.status).toBe(
            400,
        );
        const malformed = send('POST', '/api/parsers/xml/preview', { sample_text: '<a><oops' })!;
        expect(malformed.status).toBe(422);
        expect(String((malformed.body as { error: string }).error)).toContain('not well-formed');
        const noMatch = send('POST', '/api/parsers/xml/preview', {
            grammar: { xml: { record_element: 'ghost' } },
            sample_text: '<a><b/></a>',
        })!;
        expect(noMatch.status).toBe(422);
        expect(String((noMatch.body as { error: string }).error)).toContain('ghost');
    });

    it('ignores unrelated routes', () => {
        expect(send('GET', '/api/pipelines')).toBeUndefined();
    });
});
