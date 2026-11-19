import { TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { of } from 'rxjs';
import { ToastrService } from 'ngx-toastr';
import { describe, expect, it, vi } from 'vitest';
import { ConnectionProfile, ConnectionsService, ConnectionTestResult } from 'app/inspecto/api';
import { SessionService } from 'app/inspecto/auth/session.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { penByLabel, penEdit, penError, refresh } from 'app/inspecto/testing/pen-field';
import { ConnectionFormDialog } from './connection-form.dialog';
import { POSTGRES_CONNECTOR, connectorLock } from './connection-types';

const TEST_OK: ConnectionTestResult = {
    id: '(unsaved)',
    connector: 'sftp',
    endpoint: 'h:22',
    reachable: true,
    secretsResolved: true,
    detail: 'ok',
};

function create(profile?: ConnectionProfile, existingIds?: string[], remoteLocked = false) {
    const close = vi.fn();
    const api = {
        create: vi.fn((p: ConnectionProfile) => of(p)),
        update: vi.fn((_id: string, p: ConnectionProfile) => of(p)),
        testProfile: vi.fn(() => of(TEST_OK)),
    };
    TestBed.configureTestingModule({
        imports: [ConnectionFormDialog],
        providers: [
            provideNoopAnimations(),
            { provide: MatDialogRef, useValue: { close } },
            { provide: MAT_DIALOG_DATA, useValue: { profile, existingIds } },
            { provide: ConnectionsService, useValue: api },
            { provide: ToastrService, useValue: { success: () => {}, error: () => {}, warning: () => {} } },
        ],
    });
    if (remoteLocked) {
        // DATA-TIERS-1 P4: what a Basic /bootstrap reports
        const session = TestBed.inject(SessionService);
        session.features.set({ remoteSystems: false });
        session.featureReasons.set({ remoteSystems: REMOTE_WHY });
    }
    const fixture = TestBed.createComponent(ConnectionFormDialog);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, close, api };
}

/** The settings rows as a person reads them: the control kind, then its visible text (label, value, hint). */
function settingsRows(el: HTMLElement): string[] {
    const grid = el.querySelector('section[aria-label="Connection settings"] .grid')!;
    return Array.from(grid.children).map((row) => {
        const kind = row.matches('inspecto-pen-field')
            ? 'pen'
            : row.matches('inspecto-option-picker')
              ? 'picker'
              : row.matches('mat-form-field')
                ? 'boxed'
                : row.querySelector('mat-checkbox')
                  ? 'check'
                  : row.tagName.toLowerCase();
        return `${kind}:${(row.textContent ?? '').replace(/\s+/g, ' ').trim()}`;
    });
}

/** Every create-time type's rows, pinned before `connection-fields` was extracted (BASIC-ELT-PROFILE-1 U2). */
const SETTINGS_ROWS: Record<string, string[]> = {
    database: [
        'picker:Database type postgres',
        'pen:Host *—',
        'pen:Port 5432',
        'pen:Database *—',
        'pen:Username —',
        'boxed:Passworda ${ENV:VAR} reference, not a raw secret',
        'pen:Default schema —',
        'picker:SSL mode require',
    ],
    ftp: [
        'pen:Host *—',
        'pen:Port 21',
        'pen:Username —',
        'boxed:Passworda ${ENV:VAR} reference, not a raw secret',
        'pen:Base path —',
        'check:Passive mode',
        'pen:Encoding UTF-8',
    ],
    ftps: [
        'pen:Host *—',
        'pen:Port 990',
        'pen:Username —',
        'boxed:Passworda ${ENV:VAR} reference, not a raw secret',
        'pen:Base path —',
        'check:Implicit TLS',
        'check:Passive mode',
    ],
    local: ['pen:Directory *—', 'pen:File pattern —glob, e.g. *.csv.gz', 'check:Recurse subdirectories'],
    sftp: [
        'pen:Host *—',
        'pen:Port 22',
        'pen:Username *—',
        'picker:Auth method password',
        'boxed:Password / passphrasea ${ENV:VAR} reference, not a raw secret',
        'pen:Private key path —path to the private key (key auth)',
        'pen:Remote directory —',
        'check:Strict host-key checking',
    ],
    https: [
        'pen:Host *—',
        'pen:Port 443',
        'pen:Path —e.g. /hooks/orders',
        'boxed:Bearer tokena ${ENV:VAR} reference, not a raw secret',
        'pen:Request timeout (s) 10',
    ],
};

const REMOTE_WHY =
    'Collecting from remote systems (SFTP, FTP, object stores, databases, Kafka) is in Standard and above; Basic adds files and copies Postgres tables.';

describe('ConnectionFormDialog', () => {
    it('DATA-TIERS-1 P4: where remote systems are withheld, a new Connection starts on Local and a remote type shows locked', async () => {
        const { fixture, c, api } = create(undefined, [], true);
        expect(c.connType()).toBe('local');
        expect(c.typeLocked()).toBe(false);
        const el = fixture.nativeElement as HTMLElement;
        expect(el.querySelector('[data-testid="locked-reason"]')).toBeNull();

        c.onTypeChange('sftp');
        fixture.detectChanges();
        expect(c.typeLocked()).toBe(true);
        expect(el.querySelector('[data-testid="locked-reason"]')?.textContent).toContain(REMOTE_WHY);
        const submit = el.querySelector('button[type="submit"]') as HTMLButtonElement;
        expect(submit.disabled).toBe(true);
        c.submit();
        expect(c.step()).toBe('config'); // a locked type never reaches the save step
        expect(api.create).not.toHaveBeenCalled();
        await expectNoA11yViolations(fixture.nativeElement);

        // a database Connection is remote too (the db Collector, publish.postgres); https (delivery) is not
        c.onTypeChange('database');
        expect(c.typeLocked()).toBe(true);
        c.onTypeChange('https');
        expect(c.typeLocked()).toBe(false);
    });

    it('DATA-TIERS-1 P4: editing a saved remote Connection stays open where the edition withholds new ones', () => {
        const { c } = create({ id: 'sftp_prod', connector: 'sftp', host: 'h' } as ConnectionProfile, [], true);
        expect(c.connType()).toBe('sftp');
        expect(c.remoteLock()).toBeNull();
        expect(c.typeLocked()).toBe(false);
    });

    it('DATA-TIERS-1 P4: where the edition offers remote systems nothing is locked (SFTP stays the default)', () => {
        const { c } = create();
        expect(c.remoteLock()).toBeNull();
        c.onTypeChange('database');
        expect(c.typeLocked()).toBe(false);
    });

    it('defaults to SFTP on the config step with routing collapsed and both hops unselected', () => {
        const { c } = create();
        expect(c.connType()).toBe('sftp');
        expect(c.step()).toBe('config');
        expect(c.routingOpen()).toBe(false);
        expect(c.tunnelEnabled()).toBe(false);
        expect(c.proxyEnabled()).toBe(false);
        expect(c.attrsForm().get('host')).toBeTruthy();
        expect(c.attrsForm().get('auth_method')!.value).toBe('password');
    });

    it('rebuilds the attribute sheet on connection-type change', () => {
        const { c } = create();
        c.onTypeChange('database');
        expect(c.connType()).toBe('database');
        expect(c.attrsForm().get('database')).toBeTruthy();
        expect(c.attrsForm().get('port')!.value).toBe(5432);
    });

    it('asks name + description only at save time, pre-filling <type>_<host>', () => {
        const { c, api, close } = create();
        c.onTypeChange('database');
        c.attrsForm().patchValue({ host: 'pg.example.com', database: 'warehouse', sslmode: 'require' });
        c.submit(); // config valid → advances to the save step, does NOT create yet
        expect(c.step()).toBe('save');
        expect(api.create).not.toHaveBeenCalled();
        expect(c.saveForm.controls.name.value).toBe('database_pg.example.com');

        c.saveForm.patchValue({ name: 'pg_main', description: 'main warehouse' });
        c.saveForm.controls.name.markAsDirty();
        c.submit();
        expect(api.create).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'pg_main',
                connector: 'db',
                host: 'pg.example.com',
                database: 'warehouse',
                description: 'main warehouse',
                options: expect.objectContaining({ sslmode: 'require', db_type: 'postgres' }),
            }),
        );
        expect(close).toHaveBeenCalledWith({ saved: expect.objectContaining({ id: 'pg_main' }) });
    });

    it('onboards an https webhook target: endpoint on the profile, token as a reference, no URL field', () => {
        const { c, api } = create();
        c.onTypeChange('https');
        expect(c.attrsForm().get('port')!.value).toBe(443);
        expect(c.attrsForm().get('url')).toBeNull();
        c.attrsForm().patchValue({
            host: 'hooks.example.com',
            basePath: '/hooks/orders',
            password: '${ENV:ORDERS_HOOK_TOKEN}',
        });
        c.submit();
        c.submit();
        expect(api.create).toHaveBeenCalledWith(
            expect.objectContaining({
                connector: 'https',
                host: 'hooks.example.com',
                port: 443,
                basePath: '/hooks/orders',
                password: '${ENV:ORDERS_HOOK_TOKEN}',
                options: expect.objectContaining({ timeout_seconds: '10' }),
            }),
        );
    });

    it('rejects a duplicate name on the save step', () => {
        const { c, api } = create(undefined, ['sftp_box']);
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        expect(c.step()).toBe('save');
        c.saveForm.patchValue({ name: 'sftp_box' });
        c.submit();
        expect(api.create).not.toHaveBeenCalled();
        expect(c.saveForm.controls.name.hasError('duplicate')).toBe(true);
    });

    it('keeps a hand-edited name when going Back and Continue again', () => {
        const { c } = create();
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        c.saveForm.patchValue({ name: 'my_own_name' });
        c.saveForm.controls.name.markAsDirty();
        c.backToConfig();
        expect(c.step()).toBe('config');
        c.submit();
        expect(c.saveForm.controls.name.value).toBe('my_own_name');
    });

    it('includes the SSH tunnel + proxy when enabled with a host', () => {
        const { c, api } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.tunnelEnabled.set(true);
        c.form.controls.tunnel.patchValue({ host: 'bastion', port: 22, username: 'jump' });
        c.proxyEnabled.set(true);
        c.form.controls.proxy.patchValue({ type: 'SOCKS5', host: 'proxy.example.com', port: 1080 });
        c.submit(); // → save step
        c.submit(); // pre-filled name is valid → create
        const p = api.create.mock.calls[0][0] as ConnectionProfile;
        expect(p.id).toBe('sftp_sftp.example.com');
        expect(p.tunnel?.host).toBe('bastion');
        expect(p.proxy).toEqual(expect.objectContaining({ type: 'SOCKS5', host: 'proxy.example.com', port: 1080 }));
    });

    it('tests the proxy hop without saving', () => {
        const { c, api } = create();
        c.proxyEnabled.set(true);
        c.form.controls.proxy.patchValue({ type: 'HTTP', host: 'proxy.example.com', port: 8080 });
        c.testProxy();
        expect(api.testProfile).toHaveBeenCalledWith(
            expect.objectContaining({ proxy: expect.objectContaining({ host: 'proxy.example.com' }) }),
            'proxy',
        );
        expect(c.proxyResult()?.reachable).toBe(true);
    });

    it('keeps routing collapsed on edit even when a tunnel is configured', () => {
        const { c } = create({ id: 'x', connector: 'sftp', tunnel: { host: 'bastion' } });
        expect(c.routingOpen()).toBe(false); // collapsed — rarely needed; expand on demand
        expect(c.tunnelEnabled()).toBe(true); // …but the stored hop is reflected once expanded
    });

    it('does not advance to the save step when a required attribute is missing', () => {
        const { c, api } = create();
        c.onTypeChange('database'); // host + database required
        c.submit();
        expect(c.step()).toBe('config');
        expect(api.create).not.toHaveBeenCalled();
    });

    it('edits an existing profile directly — no save step, id + description preserved', () => {
        const { c, api } = create({
            id: 'cdr_sftp',
            connector: 'sftp',
            host: 'h',
            description: 'prod drops',
            tunnel: { host: 'bastion' },
            options: { auth_method: 'key' },
        });
        expect(c.connType()).toBe('sftp');
        expect(c.isEdit).toBe(true);
        expect(c.tunnelEnabled()).toBe(true);
        expect(c.attrsForm().get('auth_method')!.value).toBe('key');
        c.attrsForm().patchValue({ username: 'u2' });
        c.submit();
        expect(api.update).toHaveBeenCalledWith(
            'cdr_sftp',
            expect.objectContaining({ id: 'cdr_sftp', description: 'prod drops', username: 'u2' }),
        );
    });

    it('tests the entered connection without saving', () => {
        const { c, api } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com', username: 'u' });
        c.testConnection();
        expect(api.testProfile).toHaveBeenCalled();
        expect(c.testResult()?.reachable).toBe(true);
    });

    it('has no a11y violations on either step', async () => {
        const { fixture, c } = create();
        await expectNoA11yViolations(fixture.nativeElement);
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        fixture.detectChanges();
        await expectNoA11yViolations(fixture.nativeElement);
    });

    it('shows "Name is required." only after save — never when the save step first renders', () => {
        const { fixture, c } = create();
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        expect(c.step()).toBe('save');
        c.saveForm.controls.name.setValue('');
        refresh(fixture);
        const section = fixture.nativeElement.querySelector(
            'section[aria-label="Name this connection"]',
        ) as HTMLElement;
        const field = penByLabel(section, 'Name')!;
        // Untouched: no error line under the row.
        expect(penError(field)).toBe('');
        expect(field.textContent).not.toContain('Name is required.');
        c.submit();
        refresh(fixture);
        expect(penError(field)).toBe('Name is required.');
    });

    it('names a bad pattern and a duplicate under the Name row, word for word', () => {
        const { fixture, c } = create(undefined, ['sftp_box']);
        c.attrsForm().patchValue({ host: 'h', username: 'u' });
        c.submit();
        refresh(fixture);
        const field = penByLabel(fixture.nativeElement, 'Name')!;
        penEdit(fixture, field, '-bad');
        expect(penError(field)).toBe('Start with a letter or digit; then letters, digits, . _ - only.');
        penEdit(fixture, field, 'sftp_box');
        expect(penError(field)).toBe('A connection with this name already exists.');
    });

    it('marks a missing required attribute under its row only once the form was submitted', () => {
        const { fixture, c } = create();
        c.onTypeChange('database');
        refresh(fixture);
        const host = penByLabel(fixture.nativeElement, 'Host')!;
        expect(penError(host)).toBe('');
        c.submit();
        refresh(fixture);
        expect(penError(host)).toBe('Host is required.');
    });

    it('ignores a re-pick of the current connection type, keeping the attributes already entered', () => {
        const { c } = create();
        c.attrsForm().patchValue({ host: 'sftp.example.com' });
        c.onTypeChange('sftp');
        expect(c.attrsForm().get('host')!.value).toBe('sftp.example.com');
    });

    it('BASIC-ELT-PROFILE-1 U2: renders every type’s settings rows as before (control, label, value, hint)', () => {
        const { fixture, c } = create();
        const got: Record<string, string[]> = {};
        for (const type of ['database', 'ftp', 'ftps', 'local', 'sftp', 'https']) {
            c.onTypeChange(type);
            refresh(fixture);
            got[type] = settingsRows(fixture.nativeElement);
        }
        expect(got).toEqual(SETTINGS_ROWS);
    });

    it('BASIC-ELT-PROFILE-1 U2: an edited profile’s stored values show in its rows, and a typed row writes the form', () => {
        const { fixture, c } = create({
            id: 'cdr_sftp',
            connector: 'sftp',
            host: 'sftp.example.com',
            username: 'drop',
            password: '${ENV:SFTP_PW}',
            options: { strict_host_key: 'false' },
        } as ConnectionProfile);
        const el = fixture.nativeElement as HTMLElement;
        expect(settingsRows(el)).toEqual([
            'pen:Host * sftp.example.com',
            'pen:Port 22',
            'pen:Username * drop',
            'picker:Auth method password',
            'boxed:Password / passphrasea ${ENV:VAR} reference, not a raw secret',
            'pen:Private key path —path to the private key (key auth)',
            'pen:Remote directory —',
            'check:Strict host-key checking',
        ]);
        expect(c.attrsForm().get('password')!.value).toBe('${ENV:SFTP_PW}');
        expect(c.attrsForm().get('strict_host_key')!.value).toBe(false);
        penEdit(fixture, penByLabel(el, 'Host')!, 'sftp2.example.com');
        const box = el.querySelector<HTMLInputElement>(
            'section[aria-label="Connection settings"] mat-form-field input',
        )!;
        box.value = '${ENV:SFTP_PW2}';
        box.dispatchEvent(new Event('input'));
        fixture.detectChanges();
        expect(c.attrsForm().get('host')!.value).toBe('sftp2.example.com');
        expect(c.attrsForm().get('password')!.value).toBe('${ENV:SFTP_PW2}');
    });

    it('BASIC-ELT-PROFILE-1 U2: a new Connection is never a Postgres table copy (Add data makes those)', () => {
        const { c } = create();
        expect(c.typeOptions.map((o) => o.value)).toEqual(['database', 'ftp', 'ftps', 'local', 'sftp', 'https']);
    });

    it('BASIC-ELT-PROFILE-1 U2: editing any other Connection never offers the Postgres table copy type', () => {
        const { c } = create({ id: 'cdr_sftp', connector: 'sftp', host: 'h' } as ConnectionProfile);
        expect(c.typeOptions.map((o) => o.value)).toEqual(['database', 'ftp', 'ftps', 'local', 'sftp', 'https']);
    });

    it('BASIC-ELT-PROFILE-1 U2: editing a snapshot’s postgres Connection keeps its connector and what it copies', () => {
        const { fixture, c, api } = create(
            {
                id: 'sales_postgres',
                connector: 'postgres',
                host: 'db.internal',
                port: 5432,
                database: 'sales',
                username: 'reader',
                password: '${ENV:INSPECTO_PG_SALES}',
                options: { schema: 'public', table: 'transfers' },
            },
            [],
            true,
        );
        expect(c.connType()).toBe('postgres');
        expect(c.typeOptions.map((o) => o.value)).toContain('postgres');
        expect(c.typeLocked()).toBe(false);
        const rows = settingsRows(fixture.nativeElement);
        const starts = [
            'pen:Host',
            'pen:Port',
            'pen:Database',
            'pen:Username',
            'boxed:Password',
            'pen:Schema',
            'pen:Table',
            'pen:Query',
        ];
        expect(rows.length).toBe(starts.length);
        starts.forEach((s, i) => expect(rows[i].startsWith(s), rows[i]).toBe(true));
        c.attrsForm().patchValue({ table: 'daily' });
        c.submit();
        expect(api.update).toHaveBeenCalledWith('sales_postgres', {
            id: 'sales_postgres',
            connector: 'postgres',
            host: 'db.internal',
            port: 5432,
            database: 'sales',
            username: 'reader',
            password: '${ENV:INSPECTO_PG_SALES}',
            options: { schema: 'public', table: 'daily' },
        });
    });

    it('BASIC-ELT-PROFILE-1 U2: a Postgres table copy has no Routing section; every other type keeps it', () => {
        const routing = (el: HTMLElement) =>
            Array.from(el.querySelectorAll('button')).some((b) => b.textContent?.includes('Routing'));
        const { fixture, c, api } = create({
            id: 'sales_postgres',
            connector: 'postgres',
            host: 'db.internal',
            database: 'sales',
            username: 'reader',
            password: '${ENV:INSPECTO_PG_SALES}',
            options: { schema: 'public', table: 'transfers' },
        });
        const el = fixture.nativeElement as HTMLElement;
        expect(routing(el)).toBe(false);
        expect(el.querySelector('#routing-panel')).toBeNull();
        // a hop left on from another type is never sent for postgres (the server refuses both)
        c.tunnelEnabled.set(true);
        c.form.controls.tunnel.patchValue({ host: 'bastion' });
        c.submit();
        expect((api.update.mock.calls[0][1] as ConnectionProfile).tunnel).toBeUndefined();
        for (const type of ['database', 'ftp', 'ftps', 'local', 'sftp', 'https']) {
            c.onTypeChange(type);
            refresh(fixture);
            expect(routing(el), type).toBe(true);
        }
    });

    it('BASIC-ELT-PROFILE-1 U2: a postgres Connection’s password must name an INSPECTO_PG_ variable (the server’s rule)', () => {
        const { fixture, c, api } = create({
            id: 'sales_postgres',
            connector: 'postgres',
            host: 'db.internal',
            database: 'sales',
            username: 'reader',
            password: '${ENV:INSPECTO_PG_SALES}',
            options: { schema: 'public', table: 'transfers' },
        });
        c.attrsForm().patchValue({ password: '${ENV:AWS_SECRET_ACCESS_KEY}' });
        c.submit();
        fixture.detectChanges();
        expect(api.update).not.toHaveBeenCalled();
        const box = fixture.nativeElement.querySelector('section[aria-label="Connection settings"] mat-form-field');
        expect(box.querySelector('mat-error')?.textContent).toContain('Write it as ${ENV:INSPECTO_PG_…}');
    });

    it('BASIC-ELT-PROFILE-1 D5: one rule locks a new Connection — remote connectors only; postgres, local and https are open', () => {
        const locks = { remoteSystems: REMOTE_WHY };
        for (const remote of ['db', 'ftp', 'ftps', 'sftp', 's3', 'azure', 'gcs', 'kafka'])
            expect(connectorLock(remote, locks), remote).toBe(REMOTE_WHY);
        for (const open of [POSTGRES_CONNECTOR, 'local', 'https']) expect(connectorLock(open, locks), open).toBeNull();
        expect(connectorLock('db', {})).toBeNull();
    });

    it('keeps the secret fields boxed — a password reference never shows in a closed row', () => {
        const { fixture } = create();
        const el = fixture.nativeElement as HTMLElement;
        expect(penByLabel(el, 'Password')).toBeNull();
        const boxed = Array.from(el.querySelectorAll('mat-form-field')).map((f) =>
            f.querySelector('mat-label')?.textContent?.trim(),
        );
        expect(boxed).toContain('Password / passphrase');
    });
});
