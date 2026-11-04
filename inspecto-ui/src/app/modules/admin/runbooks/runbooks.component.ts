import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { ToastrService } from 'ngx-toastr';
import { ComponentDef, ComponentsService } from 'app/inspecto/api/components.service';
import { apiErrorMessage } from 'app/inspecto/api/api-base';
import { LensService } from 'app/inspecto/api/lens.service';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { InspectoOptionPickerComponent } from 'app/inspecto/components/option-picker.component';
import { InspectoPageHeaderComponent } from 'app/inspecto/components/page-header.component';
import { PenFieldComponent } from 'app/inspecto/components/pen-field.component';
import {
    RUNBOOK_LINK_KINDS,
    RunbookLinkKind,
    runbookFromContent,
    runbookToContent,
} from 'app/inspecto/runbook/runbook-model';

const NO_LINK = '';

/**
 * Runbooks (operator 2026-10-10): author the linked guidance an Alert Rule names by `runbook:`. A list and one
 * editor — title, summary, ordered steps (text plus an optional link to a Dataset, Query, Dashboard or Case), owner
 * role and tags. Saves through `/components/runbook` (canAuthorWorkbench), so history and restore are the store's.
 */
@Component({
    selector: 'app-runbooks',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [
        ReactiveFormsModule,
        MatButtonModule,
        MatIconModule,
        InspectoEmptyStateComponent,
        InspectoOptionPickerComponent,
        InspectoPageHeaderComponent,
        PenFieldComponent,
    ],
    template: `
        <inspecto-page-header
            title="Runbooks"
            subtitle="Guidance an Alert Rule links; its Incident or Case page shows the Runbook."
            [terms]="['Runbook', 'Alert Rule', 'Incident']"
        >
            @if (lens.canAuthorWorkbench()) {
                <button actions mat-flat-button color="primary" type="button" (click)="startNew()">New Runbook</button>
            }
        </inspecto-page-header>
        <div class="flex flex-col gap-6 p-6 md:flex-row">
            <nav class="md:w-64" aria-label="Runbooks">
                @if (runbooks().length) {
                    <ul class="flex flex-col gap-1">
                        @for (r of runbooks(); track r.name) {
                            <li>
                                <button
                                    type="button"
                                    class="w-full rounded px-2 py-1 text-left text-sm"
                                    [class.font-semibold]="editingId() === r.name"
                                    [attr.aria-current]="editingId() === r.name ? 'true' : null"
                                    (click)="edit(r)"
                                >
                                    {{ titleOf(r) }}
                                </button>
                            </li>
                        }
                    </ul>
                } @else {
                    <inspecto-empty-state title="No Runbooks" message="Create one, then link it from an Alert Rule." />
                }
            </nav>
            @if (open()) {
                <form class="flex flex-1 flex-col gap-2" [formGroup]="form" (ngSubmit)="save()" aria-label="Runbook">
                    <h2 class="text-lg font-semibold">{{ editingId() ? 'Edit ' + editingId() : 'New Runbook' }}</h2>
                    @if (!editingId()) {
                        <inspecto-pen-field
                            label="Id"
                            [required]="true"
                            [mono]="true"
                            formControlName="id"
                            [error]="idError()"
                        ></inspecto-pen-field>
                    }
                    <inspecto-pen-field
                        label="Title"
                        [required]="true"
                        formControlName="title"
                        [error]="titleError()"
                    ></inspecto-pen-field>
                    <inspecto-pen-field label="Summary" [multiline]="true" formControlName="summary"></inspecto-pen-field>
                    <h3 class="text-sm font-semibold">Steps</h3>
                    <ol class="flex flex-col gap-2" formArrayName="steps">
                        @for (s of steps.controls; track s; let i = $index) {
                            <li class="flex flex-col gap-1 rounded border p-2" [formGroupName]="i">
                                <inspecto-pen-field
                                    [label]="'Step ' + (i + 1)"
                                    [required]="true"
                                    [multiline]="true"
                                    formControlName="text"
                                    [error]="s.get('text')?.touched && s.get('text')?.hasError('required') ? 'A step needs text.' : ''"
                                ></inspecto-pen-field>
                                <div class="flex flex-wrap items-end gap-2">
                                    <inspecto-option-picker
                                        label="Link to"
                                        formControlName="linkKind"
                                        [options]="linkKinds"
                                    />
                                    @if (s.get('linkKind')?.value) {
                                        <inspecto-pen-field
                                            label="Link id"
                                            [mono]="true"
                                            formControlName="linkId"
                                        ></inspecto-pen-field>
                                    }
                                    <button
                                        mat-icon-button
                                        type="button"
                                        [attr.aria-label]="'Remove step ' + (i + 1)"
                                        [disabled]="steps.length === 1"
                                        (click)="steps.removeAt(i)"
                                    >
                                        <mat-icon svgIcon="heroicons_outline:trash" />
                                    </button>
                                </div>
                            </li>
                        }
                    </ol>
                    <div>
                        <button mat-stroked-button type="button" (click)="addStep()">Add step</button>
                    </div>
                    <inspecto-pen-field label="Owner role" formControlName="ownerRole"></inspecto-pen-field>
                    <inspecto-pen-field label="Tags (comma-separated)" formControlName="tags"></inspecto-pen-field>
                    <div class="flex gap-2">
                        <button mat-flat-button color="primary" type="submit" [disabled]="!lens.canAuthorWorkbench()">
                            Save
                        </button>
                        @if (editingId()) {
                            <button mat-stroked-button color="warn" type="button" (click)="remove()">Delete</button>
                        }
                        <button mat-button type="button" (click)="open.set(false)">Close</button>
                    </div>
                </form>
            }
        </div>
    `,
})
export class RunbooksComponent implements OnInit {
    private api = inject(ComponentsService);
    private fb = inject(FormBuilder);
    private toastr = inject(ToastrService);
    private confirm = inject(InspectoConfirmService);
    readonly lens = inject(LensService);

    readonly runbooks = signal<ComponentDef[]>([]);
    readonly editingId = signal<string | null>(null);
    readonly open = signal(false);
    readonly linkKinds = [{ value: NO_LINK, label: 'No link' }, ...RUNBOOK_LINK_KINDS];
    private kept: Record<string, unknown> = {};

    readonly form: FormGroup = this.fb.group({
        id: ['', [Validators.required, Validators.pattern(/^[A-Za-z0-9][A-Za-z0-9._-]*$/)]],
        title: ['', Validators.required],
        summary: [''],
        steps: this.fb.array([]),
        ownerRole: [''],
        tags: [''],
    });

    /** The id row's error line: shown once the row was left or Save was pressed. */
    idError(): string {
        const c = this.form.controls['id'];
        if (!c.touched) return '';
        if (c.hasError('required')) return 'An id is required.';
        if (c.hasError('pattern')) return 'Letters, digits, dot, dash or underscore.';
        return '';
    }

    /** The title row's error line: shown once the row was left or Save was pressed. */
    titleError(): string {
        const c = this.form.controls['title'];
        return c.touched && c.hasError('required') ? 'A title is required.' : '';
    }

    get steps(): FormArray {
        return this.form.get('steps') as FormArray;
    }

    ngOnInit(): void {
        this.load();
    }

    load(): void {
        this.api.list('runbook').subscribe({
            next: (rows) => this.runbooks.set(rows),
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not load the Runbooks')),
        });
    }

    titleOf(c: ComponentDef): string {
        return runbookFromContent(c.name, c.content ?? {}).title;
    }

    addStep(text = '', kind: string = NO_LINK, linkId = ''): void {
        this.steps.push(this.fb.group({ text: [text, Validators.required], linkKind: [kind], linkId: [linkId] }));
    }

    startNew(): void {
        this.kept = {};
        this.editingId.set(null);
        this.form.reset({ id: '', title: '', summary: '', ownerRole: '', tags: '' });
        this.steps.clear();
        this.addStep();
        this.open.set(true);
    }

    edit(c: ComponentDef): void {
        const r = runbookFromContent(c.name, c.content ?? {});
        const { title: _t, summary: _s, steps: _st, ownerRole: _o, tags: _g, name: _n, ...kept } = c.content ?? {};
        this.kept = kept;
        this.editingId.set(c.name);
        this.form.reset({
            id: c.name,
            title: r.title,
            summary: r.summary,
            ownerRole: r.ownerRole,
            tags: r.tags.join(', '),
        });
        this.steps.clear();
        for (const s of r.steps) this.addStep(s.text, s.link?.kind ?? NO_LINK, s.link?.id ?? '');
        if (!r.steps.length) this.addStep();
        this.open.set(true);
    }

    save(): void {
        if (this.form.invalid) {
            this.form.markAllAsTouched();
            return;
        }
        const v = this.form.getRawValue();
        const id = this.editingId() ?? String(v.id).trim();
        const content = runbookToContent(
            {
                id,
                title: v.title,
                summary: v.summary ?? '',
                ownerRole: v.ownerRole ?? '',
                tags: String(v.tags ?? '').split(','),
                steps: (v.steps as { text: string; linkKind: string; linkId: string }[]).map((s) => ({
                    text: s.text,
                    link: s.linkKind ? { kind: s.linkKind as RunbookLinkKind, id: s.linkId ?? '' } : null,
                })),
            },
            this.kept,
        );
        const call = this.editingId() ? this.api.update('runbook', id, content) : this.api.create('runbook', content);
        call.subscribe({
            next: () => {
                this.toastr.success('Runbook saved');
                this.editingId.set(id);
                this.form.markAsPristine();
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not save the Runbook')),
        });
    }

    async remove(): Promise<void> {
        const id = this.editingId();
        if (
            !id ||
            !(await this.confirm.confirmDestructive(`Delete Runbook ${id}? Alert Rules that link it keep the id.`))
        )
            return;
        this.api.remove('runbook', id).subscribe({
            next: () => {
                this.open.set(false);
                this.editingId.set(null);
                this.load();
            },
            error: (e) => this.toastr.error(apiErrorMessage(e, 'Could not delete the Runbook')),
        });
    }
}
