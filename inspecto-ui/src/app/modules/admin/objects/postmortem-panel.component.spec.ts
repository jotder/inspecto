import { TestBed } from '@angular/core/testing';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';
import { describe, expect, it, vi } from 'vitest';
import { FindingsSpecDef, ObjectsService, OperationalObject } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { expectNoA11yViolations } from 'app/inspecto/testing/a11y';
import { ToastrService } from 'ngx-toastr';
import { PostmortemPanelComponent } from './postmortem-panel.component';

const POSTMORTEM = {
    commander: 'alice',
    incidentDate: '2026-07-10',
    downtime: '1 hour',
    businessImpact: 'batch SLA missed',
    timeline: [{ time: '12:00 UTC', text: 'alert fired' }],
    fiveWhys: ['parser rejected records', 'schema drift', '', '', ''],
    actions: [{ done: false, text: 'pin schema version', owner: 'ops', due: '2026-07-20' }],
};

const INCIDENT: OperationalObject = {
    id: 'i1',
    objectType: 'INCIDENT',
    title: 'Late feed',
    description: 'rejected-file spike',
    status: 'DIAGNOSING',
    priority: 'MAJOR',
    assignee: 'operator',
    attributes: {
        category: 'Data Quality / Timeliness / Late arrival',
        tags: 'urgent',
        postmortem: JSON.stringify(POSTMORTEM),
    },
    createdAt: 1,
    updatedAt: 1,
    closedAt: 0,
};

/** The built-in Findings sections the server serves when nothing is authored (C3/D6). */
const CASE_FINDINGS_SPEC: FindingsSpecDef = {
    objectType: 'case',
    sections: [
        {
            key: 'disposition',
            label: 'Disposition',
            type: 'select',
            tier: 'required',
            required: false,
            options: [
                { value: 'RECOVERED', label: 'Recovered' },
                { value: 'CONFIRMED', label: 'Confirmed' },
            ],
        },
        { key: 'impactAmount', label: 'Impact amount', type: 'string', tier: 'required', required: false },
        { key: 'recordsAffected', label: 'Records affected', type: 'string', tier: 'required', required: false },
        { key: 'summary', label: 'Summary', type: 'multiline', tier: 'required', required: false },
    ],
};

function create(object: OperationalObject = INCIDENT, findingsSpec: FindingsSpecDef | null = null) {
    // graph() feeds the CaseContentsComponent child the CASE-object tests render.
    const api = {
        update: vi.fn(() => of(object)),
        graph: vi.fn(() => of({ root: object.id, depth: 1, nodes: [], edges: [] })),
    };
    const toastr = { success: vi.fn(), error: vi.fn() };
    const confirm = { confirm: vi.fn(() => Promise.resolve(true)) };
    TestBed.configureTestingModule({
        imports: [PostmortemPanelComponent],
        providers: [
            provideNoopAnimations(),
            provideRouter([]),
            { provide: ObjectsService, useValue: api },
            { provide: ToastrService, useValue: toastr },
            { provide: InspectoConfirmService, useValue: confirm },
        ],
    });
    const fixture = TestBed.createComponent(PostmortemPanelComponent);
    fixture.componentRef.setInput('object', object);
    fixture.componentRef.setInput('findingsSpec', findingsSpec);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, api, toastr, confirm };
}

describe('PostmortemPanelComponent', () => {
    it('populates the postmortem template, migrating the legacy fiveWhys shape onto causeAnalysis (I2)', () => {
        const { c } = create();
        expect(c.form.controls.commander.value).toBe('alice');
        expect(c.timeline.length).toBe(1);
        expect(c.causeAnalysis.length).toBe(5); // legacy fiveWhys rows carried over 1:1
        expect(c.form.controls.causeMethod.value).toBe('The 5 Whys'); // the default method label
        expect(c.actionsArr.length).toBe(1);
    });

    it('saves the edited postmortem as an attributes patch (causeAnalysis shape)', () => {
        const { c, api } = create();
        c.form.controls.downtime.setValue('2 hours');
        c.removeCause(4); // rows are add/removable now — not pinned to exactly five
        c.form.markAsDirty();
        c.save();
        expect(api.update).toHaveBeenCalledTimes(1);
        const [id, patch] = (api.update as ReturnType<typeof vi.fn>).mock.calls[0];
        expect(id).toBe('i1');
        const saved = JSON.parse(patch.attributes.postmortem);
        expect(saved.downtime).toBe('2 hours');
        expect(saved.commander).toBe('alice');
        expect(saved.causeMethod).toBe('The 5 Whys');
        expect(saved.causeAnalysis).toHaveLength(4);
        expect(saved.fiveWhys).toBeUndefined();
    });

    it('round-trips case Findings + team + target date as one attributes patch (C3/C6)', () => {
        const CASE: OperationalObject = {
            ...INCIDENT,
            id: 'c1',
            objectType: 'CASE',
            status: 'INVESTIGATING',
            attributes: { assignees: 'alice,bob', targetDate: '2026-07-01' },
        };
        const { fixture, c, api } = create(CASE, CASE_FINDINGS_SPEC);
        expect(c.teamForm.controls.team.value).toBe('alice, bob');
        expect(c.teamForm.controls.targetDate.value).toBe('2026-07-01');
        expect(c.isTargetOverdue(CASE)).toBe(true); // past target on an active case → overdue hint

        // The Findings half is schema-driven now (D6) — drive its controls, not a fixed FormGroup.
        const schema = c.findingsSchema()!;
        expect(Object.keys(schema.form.controls).sort()).toEqual([
            'disposition',
            'impactAmount',
            'recordsAffected',
            'summary',
        ]);
        schema.form.patchValue({ disposition: 'RECOVERED', impactAmount: '99' });
        c.teamForm.patchValue({ team: 'alice, carol ' });
        c.teamForm.markAsDirty();
        fixture.detectChanges();

        c.saveFindings();
        const [id, patch] = (api.update as ReturnType<typeof vi.fn>).mock.calls[0];
        expect(id).toBe('c1');
        expect(patch.attributes.assignees).toBe('alice,carol');
        expect(patch.attributes.targetDate).toBe('2026-07-01');
        expect(JSON.parse(patch.attributes.findings).disposition).toBe('RECOVERED');
        // The flat, queryable copy the C4 analytics roll-up sums still rides along.
        expect(patch.attributes.impactAmount).toBe('99');
    });

    it('renders the deployment-authored section set instead of the built-in one (D6)', () => {
        const CASE: OperationalObject = {
            ...INCIDENT,
            id: 'c3',
            objectType: 'CASE',
            status: 'INVESTIGATING',
            attributes: {},
        };
        const { c, api } = create(CASE, {
            objectType: 'case',
            sections: [
                {
                    key: 'outcome',
                    label: 'Outcome',
                    type: 'select',
                    tier: 'required',
                    required: false,
                    options: [{ value: 'WIN', label: 'Win' }],
                },
                // An unrenderable type is skipped rather than drawn as a broken control.
                { key: 'bogus', label: 'Bogus', type: 'rocket', tier: 'required' },
            ],
        });
        const schema = c.findingsSchema()!;
        expect(Object.keys(schema.form.controls)).toEqual(['outcome']);

        schema.form.patchValue({ outcome: 'WIN' });
        c.saveFindings();
        const [, patch] = (api.update as ReturnType<typeof vi.fn>).mock.calls[0];
        expect(JSON.parse(patch.attributes.findings)).toEqual({ outcome: 'WIN' });
        // `disposition` is not configured here, so no flat impact copy is fabricated.
        expect(patch.attributes.impactAmount).toBe('');
    });

    it('drops the soft no-disposition gate when the section is not configured (D6)', async () => {
        const CASE: OperationalObject = {
            ...INCIDENT,
            id: 'c4',
            objectType: 'CASE',
            status: 'INVESTIGATING',
            attributes: {},
        };
        const { c, confirm } = create(CASE, {
            objectType: 'case',
            sections: [{ key: 'outcome', label: 'Outcome', type: 'string', tier: 'required', required: false }],
        });
        await c.onAct('resolve');
        expect(confirm.confirm).not.toHaveBeenCalled(); // nothing to warn about
    });

    it('still warns on resolve when disposition IS configured but empty (C3)', async () => {
        const CASE: OperationalObject = {
            ...INCIDENT,
            id: 'c5',
            objectType: 'CASE',
            status: 'INVESTIGATING',
            attributes: {},
        };
        const { c, confirm } = create(CASE, CASE_FINDINGS_SPEC);
        await c.onAct('resolve');
        expect(confirm.confirm).toHaveBeenCalledTimes(1);
        expect(String((confirm.confirm as ReturnType<typeof vi.fn>).mock.calls[0][0])).toContain(
            'no disposition recorded',
        );
    });

    it('case quick actions derive from the effective workflow (C6)', () => {
        const CASE: OperationalObject = {
            ...INCIDENT,
            id: 'c2',
            objectType: 'CASE',
            status: 'INVESTIGATING',
            attributes: {},
        };
        const { c } = create(CASE);
        expect(c.quickActions.map((a) => a.id).sort()).toEqual(['escalate', 'resolve']); // built-in fallback
    });

    it('offers the lifecycle quick actions for the object status', () => {
        const { fixture, c } = create();
        expect(c.quickActions.map((a) => a.id)).toEqual(['resolve', 'archive', 'escalate']);
        fixture.componentRef.setInput('object', { ...INCIDENT, status: 'IDENTIFIED' });
        fixture.detectChanges();
        expect(c.quickActions.map((a) => a.id)).toContain('accept');
    });

    it('renders with no a11y violations', async () => {
        const { fixture } = create();
        await expectNoA11yViolations(fixture.nativeElement);
    });
});
