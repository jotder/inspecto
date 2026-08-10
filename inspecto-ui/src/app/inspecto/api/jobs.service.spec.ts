import { describe, expect, it } from 'vitest';
import { JobUpsert, jobFromWire, jobToWire } from './jobs.service';

/**
 * The job write/read wire contract. The body a job endpoint accepts is the `job:` TOON section in JSON —
 * flat and snake_case — and the server sweeps any unrecognised top-level key into the job's parameters
 * instead of rejecting it. So a camelCase key is not a validation error, it is a silently untriggered
 * job; these tests exist to keep the adapter honest. Server-side twin: `ControlApiJobCrudTest`.
 */
describe('jobToWire', () => {
    const base: JobUpsert = { name: 'j', type: 'maintenance', enabled: true };

    it('spells the trigger keys the way the server reads them', () => {
        expect(jobToWire({ ...base, onPipeline: 'cdr_ingest' })).toMatchObject({ on_pipeline: 'cdr_ingest' });
        expect(jobToWire({ ...base, onSignal: 'dataset.write', when: "$signal.dataset == 'x'" })).toMatchObject({
            on_signal: 'dataset.write',
            when: "$signal.dataset == 'x'",
        });
        expect(jobToWire({ ...base, catchUp: true })).toMatchObject({ catch_up: true });
    });

    it('never emits the camelCase spellings that would become inert params', () => {
        const body = jobToWire({ ...base, onPipeline: 'p', onSignal: 's', catchUp: true });
        expect(body['onPipeline']).toBeUndefined();
        expect(body['onSignal']).toBeUndefined();
        expect(body['catchUp']).toBeUndefined();
    });

    it('flattens parameters beside the config keys rather than nesting them', () => {
        const body = jobToWire({ ...base, params: { task: 'cleanup', retention_days: '30' } });
        expect(body).toMatchObject({ task: 'cleanup', retention_days: '30' });
        expect(body['params']).toBeUndefined();
    });

    it('omits blank optionals, so an unset trigger is absent rather than empty', () => {
        const body = jobToWire({ ...base, cron: '', onPipeline: null, when: null, params: { a: '', b: null } });
        expect(body['cron']).toBeUndefined();
        expect(body['on_pipeline']).toBeUndefined();
        expect(body['a']).toBeUndefined();
        expect(body['b']).toBeUndefined();
        // a blank on_signal would match EVERY signal server-side if it ever reached matchesType
        expect(jobToWire({ ...base, onSignal: '' })['on_signal']).toBeUndefined();
    });

    it('refuses to let a parameter shadow a config key', () => {
        const body = jobToWire({ ...base, cron: '0 0 6 * * *', params: { cron: 'sneaky', on_signal: 'sneaky' } });
        expect(body['cron']).toBe('0 0 6 * * *');
        expect(body['on_signal']).toBeUndefined();
    });
});

describe('jobFromWire', () => {
    it('maps the flat section back, treating every non-config key as a parameter', () => {
        const d = jobFromWire({
            name: 'on_dataset_write', type: 'maintenance', on_signal: 'dataset.write',
            when: "$signal.dataset == 'x'", enabled: true, catch_up: true, task: 'cleanup', retention_days: '30',
        });
        expect(d.onSignal).toBe('dataset.write');
        expect(d.when).toBe("$signal.dataset == 'x'");
        expect(d.catchUp).toBe(true);
        expect(d.params).toEqual({ task: 'cleanup', retention_days: '30' });
    });

    it('round-trips an upsert through the wire and back', () => {
        const original: JobUpsert = {
            name: 'j', type: 'maintenance', onSignal: 'dataset.*', when: '$signal.rows > 0',
            enabled: true, catchUp: true, params: { task: 'cleanup' },
        };
        const back = jobFromWire(jobToWire(original));
        expect(back).toMatchObject({
            name: 'j', type: 'maintenance', onSignal: 'dataset.*', when: '$signal.rows > 0',
            enabled: true, catchUp: true, params: { task: 'cleanup' },
        });
    });

    it('defaults a job with no explicit enabled flag to armed, as the server does', () => {
        expect(jobFromWire({ name: 'j', type: 'maintenance' }).enabled).toBe(true);
        expect(jobFromWire({ name: 'j', type: 'maintenance', enabled: false }).enabled).toBe(false);
    });
});
