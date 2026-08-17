import { Component, OnInit, ViewEncapsulation, computed, inject, signal, ChangeDetectionStrategy } from '@angular/core';
import { FormControl, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { ColDef } from 'ag-grid-community';
import { ToastrService } from 'ngx-toastr';
import { apiErrorMessage, LensService, ObjectsService, Tag, TagAssignment, TagsService } from 'app/inspecto/api';
import { InspectoConfirmService } from 'app/inspecto/confirm.service';
import { InspectoEmptyStateComponent } from 'app/inspecto/components/empty-state.component';
import { DataTableComponent } from 'app/inspecto/data-table';
import { fmtDateTime, InspectoRowAction } from 'app/inspecto/grid';
import { AiExplainComponent } from 'app/inspecto/ai-assist/ai-explain.component';

/**
 * **Tags** (BACKLOG D7) — the operator surface over the cross-entity label graph. Picking a tag from the
 * vocabulary answers the question the feature exists for: *everything carrying this label, across kinds*
 * (`GET /tags/{name}/targets`), which no per-entity tag field could answer without a fan-out over every
 * store.
 *
 * Three things about this pane follow from the backend's design rather than from UI preference
 * (`okf/backend/control-plane/tags.md`):
 *
 * - **Counts are per-caller.** The target list is filtered server-side to what the caller may see, so two
 *   users can legitimately get different counts for one tag. The count shown is "targets you can see",
 *   which is why it is derived from the fetched list and never cached across tags.
 * - **Rename is one operation, not a loop.** It moves the registry entry, the edges, every object's CSV
 *   projection and any Tag Rule together; renaming onto an existing tag **merges** them deliberately.
 * - **Delete is 409 while a Tag Rule still applies the tag** — the rule would resurrect it on the next
 *   matching object. That surfaces as the server's message, not as a client-side pre-check, so the rule
 *   stays the single source of truth about its own existence.
 *
 * Applying a tag to an arbitrary target is deliberately **not** here — assignment belongs next to the
 * thing being labelled (the mail pane's tag menu), not in a vocabulary admin screen. This pane removes
 * assignments, which is the half that has no other home.
 */
@Component({
    selector: 'app-tags',
    standalone: true,
    imports: [
        AiExplainComponent,
        MatButtonModule,
        MatIconModule,
        MatProgressSpinnerModule,
        MatFormFieldModule,
        MatInputModule,
        ReactiveFormsModule,
        DataTableComponent,
        InspectoEmptyStateComponent,
    ],
    templateUrl: './tags.component.html',
    changeDetection: ChangeDetectionStrategy.OnPush,
    encapsulation: ViewEncapsulation.None,
})
export class TagsComponent implements OnInit {
    private api = inject(TagsService);
    private objects = inject(ObjectsService);
    private confirm = inject(InspectoConfirmService);
    private toastr = inject(ToastrService);
    protected lens = inject(LensService);

    readonly registry = signal<Tag[]>([]);
    readonly selected = signal<string | null>(null);
    readonly targets = signal<TagAssignment[]>([]);
    readonly loadingTags = signal(false);
    readonly loadingTargets = signal(false);

    /** Inline rename, opened from the header — a single field does not warrant a dialog. */
    readonly renaming = signal(false);
    readonly renameCtrl = new FormControl('', {
        nonNullable: true,
        validators: [Validators.required, Validators.pattern(/^[^,]*$/)],
    });

    readonly canAuthor = computed(() => this.lens.canAuthorWorkbench());

    readonly columnDefs: ColDef<TagAssignment>[] = [
        { field: 'targetKind', headerName: 'Kind', width: 180 },
        { field: 'targetId', headerName: 'Target', flex: 1 },
        { field: 'actor', headerName: 'Tagged by', width: 160 },
        {
            field: 'createdAt',
            headerName: 'Tagged',
            width: 180,
            sort: 'desc',
            valueFormatter: (p) => fmtDateTime(p.value),
        },
    ];

    readonly rowActions: InspectoRowAction<TagAssignment>[] = [
        {
            icon: 'heroicons_outline:x-mark',
            hint: 'Remove this tag from the target',
            onClick: (a) => this.untag(a),
        },
    ];

    ngOnInit(): void {
        this.loadRegistry();
    }

    loadRegistry(): void {
        this.loadingTags.set(true);
        this.objects.tags().subscribe({
            next: (tags) => {
                this.registry.set(tags);
                this.loadingTags.set(false);
                // Keep the selection if it survived; otherwise fall back to the first tag.
                const current = this.selected();
                if (current && tags.some((t) => t.name === current)) return;
                this.select(tags[0]?.name ?? null);
            },
            error: (err) => {
                this.loadingTags.set(false);
                this.toastr.error(apiErrorMessage(err, 'Could not load the tag vocabulary'));
            },
        });
    }

    select(name: string | null): void {
        this.selected.set(name);
        this.renaming.set(false);
        this.targets.set([]);
        if (!name) return;
        this.loadingTargets.set(true);
        this.api.targets(name).subscribe({
            next: (rows) => {
                // Ignore a response that lost the race with a newer selection.
                if (this.selected() !== name) return;
                this.targets.set(rows);
                this.loadingTargets.set(false);
            },
            error: (err) => {
                if (this.selected() !== name) return;
                this.loadingTargets.set(false);
                this.toastr.error(apiErrorMessage(err, `Could not load what carries “${name}”`));
            },
        });
    }

    startRename(): void {
        this.renameCtrl.setValue(this.selected() ?? '');
        this.renaming.set(true);
    }

    cancelRename(): void {
        this.renaming.set(false);
    }

    commitRename(): void {
        const from = this.selected();
        if (!from) return;
        if (this.renameCtrl.invalid) {
            this.renameCtrl.markAllAsTouched();
            return;
        }
        const to = this.renameCtrl.value.trim();
        if (!to || to === from) {
            this.renaming.set(false);
            return;
        }
        this.api.rename(from, to).subscribe({
            next: (r) => {
                this.renaming.set(false);
                this.selected.set(to);
                const merged = this.registry().some((t) => t.name === to);
                this.toastr.success(
                    merged
                        ? `Merged “${from}” into “${to}” — ${r.assignments} assignment(s) moved`
                        : `Renamed to “${to}” — ${r.assignments} assignment(s), ${r.rules} rule(s) moved`,
                );
                this.loadRegistry();
                this.select(to);
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not rename “${from}”`)),
        });
    }

    async remove(): Promise<void> {
        const name = this.selected();
        if (!name) return;
        const count = this.targets().length;
        const ok = await this.confirm.confirmDestructive(
            `Delete the tag “${name}”?\n\nIt will be removed from ${count} target(s). This does not delete ` +
                `anything the tag was applied to.`,
            { requireText: name },
        );
        if (!ok) return;
        this.api.remove(name).subscribe({
            next: (r) => {
                this.toastr.success(`Deleted “${name}” — ${r.assignments} assignment(s) removed`);
                this.selected.set(null);
                this.loadRegistry();
            },
            // A Tag Rule still applying the tag answers 409; the server's message names the rule.
            error: (err) => this.toastr.error(apiErrorMessage(err, `Could not delete “${name}”`)),
        });
    }

    async untag(a: TagAssignment): Promise<void> {
        const ok = await this.confirm.confirm(`Remove “${a.tag}” from ${a.targetKind} “${a.targetId}”?`, 'Remove tag');
        if (!ok) return;
        this.api.unassign(a.targetKind, a.targetId, a.tag).subscribe({
            next: () => {
                this.targets.update((rows) =>
                    rows.filter((r) => !(r.targetKind === a.targetKind && r.targetId === a.targetId)),
                );
            },
            error: (err) => this.toastr.error(apiErrorMessage(err, 'Could not remove the tag')),
        });
    }
}
