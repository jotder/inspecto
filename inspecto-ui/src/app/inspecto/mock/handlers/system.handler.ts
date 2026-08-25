import { MockFlags } from '../mock-flags';
import { json, match, MockHandler, MockRequest } from '../mock-http';
import { MockStore } from '../mock-store';
import { readSchedulerCap, schedulerSpaceShape, writeSchedulerCap } from './settings.handler';

const REPORT = /\/system\/operational-db$/;
const TEST = /\/system\/operational-db\/test$/;
const SCHEDULER = /\/system\/scheduler$/;

/** The ten families, in the backend roster's order (`OperationalDb.Family`). */
const FAMILIES: [string, string, string, string, string | null, string | null][] = [
    ['JOB_RUNS', 'Job runs', 'jobs.backend', 'jobs.db.url', null, null],
    ['PROVENANCE', 'Provenance', 'provenance.backend', 'provenance.db.url', null, null],
    [
        'CONSIGNMENT_OUTPUTS',
        'Consignment outputs',
        'consignment.outputs.backend',
        'consignment.outputs.db.url',
        null,
        null,
    ],
    ['FILE_STAGES', 'File stages', 'file.stages.backend', 'file.stages.db.url', null, null],
    ['OBJECTS', 'Objects', 'objects.backend', 'objects.db.url', 'objects.db.user', 'objects.db.password'],
    ['LINKS', 'Links', 'objects.backend', 'objects.links.db.url', 'objects.db.user', 'objects.db.password'],
    ['NOTES', 'Notes', 'objects.backend', 'objects.notes.db.url', 'objects.db.user', 'objects.db.password'],
    ['TAGS', 'Tag assignments', 'objects.backend', 'objects.tags.db.url', 'objects.db.user', 'objects.db.password'],
    ['STATUS', 'Status', 'status.backend', 'status.db.url', 'status.db.user', 'status.db.password'],
    ['ACQUISITION_LEDGER', 'Acquisition ledger', 'acquire.ledger.backend', 'acquire.ledger.db.url', null, null],
];

/**
 * Mock for the two PG-1 Open 2 Stage 1 diagnostics routes, so the Settings ▸ Operational database
 * section renders offline.
 *
 * <p>⚠ **A mock must never be more lenient than the server.** The real `POST …/test` 422s on a missing
 * URL, a scheme it will not dial, and — the one that matters — a **literal password**, since a literal
 * would be a credential in transit and in every access log. All three refusals are mirrored here; a
 * mock that accepted a literal would rehearse a flow the backend rejects and hide it until deploy.
 *
 * <p>The report models a default Personal deployment: DuckDB, no driver, most families off by their own
 * `*.backend` default — which is the honest offline answer, not an invented Postgres estate.
 */
export function systemHandler(flags: MockFlags): MockHandler {
    return (req: MockRequest, store: MockStore) => {
        if (!flags.mockOps) return undefined;

        // Consignment-concurrency (scheduler-system-config plan Part B): the server-wide tier plus
        // the bound space's tier and a live-occupancy snapshot. Offline there is no broker, so the
        // honest snapshot is an idle one — never invented activity.
        if (match(req.url, SCHEDULER)) {
            const shape = (): unknown => {
                const systemCap = readSchedulerCap(store, req.space, 'scheduler-system');
                return {
                    system: {
                        maxConcurrentConsignments: systemCap ?? 0,
                        source: systemCap == null ? 'default' : 'file',
                    },
                    // ONE space sub-shape, shared with /settings/scheduler — two hand-built copies of
                    // the same wire shape is exactly the drift that hid the effective-cadence fields.
                    space: schedulerSpaceShape(store, req.space),
                    cores: 8,
                    live: {
                        system_cap: systemCap ?? 0,
                        system_in_flight: 0,
                        space_in_flight: {},
                        pipelines: {},
                    },
                };
            };
            if (req.method === 'GET') return json(shape());
            if (req.method === 'PUT') {
                const { refusal } = writeSchedulerCap(store, req.space, 'scheduler-system', req.body);
                return refusal ?? json(shape());
            }
        }

        if (req.method === 'GET' && match(req.url, REPORT)) {
            return json({
                engine: 'duckdb',
                engineProperty: 'inspecto.db',
                driverAvailable: false,
                families: FAMILIES.map(([family, label, backendProperty, urlProperty, userProperty, passwordProperty]) => {
                    // Only the two families whose own backend defaults to on are enabled offline.
                    const enabled = family === 'CONSIGNMENT_OUTPUTS' || family === 'STATUS';
                    return {
                        family,
                        label,
                        enabled,
                        source: enabled ? 'SPACE_DEFAULT' : 'DISABLED',
                        url: enabled ? `jdbc:duckdb:${urlProperty.replace(/\./g, '-')}.db` : null,
                        user: null,
                        backendProperty,
                        urlProperty,
                        userProperty,
                        passwordProperty,
                    };
                }),
            });
        }

        if (req.method === 'POST' && match(req.url, TEST)) {
            const body = (req.body ?? {}) as { url?: string; password?: string };
            const url = (body.url ?? '').trim();
            if (!url) return json({ error: { message: 'url is required' } }, 422);
            if (!url.startsWith('jdbc:postgresql:') && !url.startsWith('jdbc:duckdb:'))
                return json({ error: { message: 'url must start with jdbc:postgresql: or jdbc:duckdb:' } }, 422);
            const password = (body.password ?? '').trim();
            if (password && !password.startsWith('${'))
                return json(
                    { error: { message: 'password must be a secret reference (${ENV:…}, ${KEYSTORE:…} or ${FILE:…})' } },
                    422,
                );
            // Offline there is no database to reach — answer the honest named outcome, never a fake OK.
            return json(
                url.startsWith('jdbc:postgresql:')
                    ? {
                          url,
                          outcome: 'DRIVER_MISSING',
                          detail: 'the PostgreSQL JDBC driver is not on the classpath (offline preview)',
                      }
                    : { url, outcome: 'OK', detail: 'connected and answered SELECT 1' },
            );
        }
        return undefined;
    };
}
