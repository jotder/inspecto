import { ChangeDetectionStrategy, Component, computed, inject, input, model } from '@angular/core';
import { Classification, ClassificationsService } from '../api/classifications.service';

/**
 * The **column classification picker** (UI-CLASSIFICATION-PICKER-1): a table-cell select over the Space's
 * vocabulary (`GET /classifications`), so a class the save would refuse cannot be typed. Classes that mask the column
 * are grouped first and say "(masks)" in the closed cell too; *None* clears the value.
 *
 * <p>A stored value the Space does not know is KEPT and shown, flagged "(unknown)" and `aria-invalid`, so an older
 * config opens and can be fixed rather than silently blanked. A stored value in another case (`msisdn`) shows as its
 * known class and is left as stored until the author picks. While the list loads a stored value shows unflagged; if
 * the list cannot be read the cell falls back to plain text (the save still judges).
 *
 * <p>A native `<select>`, not a dialog picker or `mat-select`: it sits in grids sized for 600+ rows, where an overlay
 * per cell is the wrong trade (the skill's table-cell exemption). Two-way `[(value)]`; no API write.
 */
@Component({
    selector: 'inspecto-classification-select',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { class: 'block' },
    template: `
        @if (vocabulary.status() === 'error') {
            <input
                type="text"
                class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-2 text-sm dark:border-gray-600"
                title="This Space's classifications could not be read, so any text is accepted here. A save still refuses a class the Space does not know."
                [value]="value() ?? ''"
                [disabled]="disabled()"
                [attr.aria-label]="label() || null"
                (input)="pick($any($event.target).value)"
            />
        } @else {
            <select
                class="h-8 w-full rounded-md border border-gray-300 bg-transparent px-1 text-sm dark:border-gray-600"
                [class.text-default]="!unknown()"
                [class.text-warn]="unknown()"
                [disabled]="disabled()"
                [attr.aria-label]="label() || null"
                [attr.aria-invalid]="unknown() ? 'true' : null"
                [attr.title]="
                    unknown()
                        ? 'This Space does not know this class, so a save refuses it. Pick a known class or None.'
                        : null
                "
                (change)="pick($any($event.target).value)"
            >
                <option value="" [selected]="!match() && !extra()">None</option>
                @if (masking().length) {
                    <optgroup label="Masks the column">
                        @for (c of masking(); track c.class) {
                            <option [value]="c.class" [selected]="c.class === match()?.class">
                                {{ optionLabel(c) }}
                            </option>
                        }
                    </optgroup>
                }
                @if (plain().length) {
                    <optgroup label="Does not mask">
                        @for (c of plain(); track c.class) {
                            <option [value]="c.class" [selected]="c.class === match()?.class">
                                {{ optionLabel(c) }}
                            </option>
                        }
                    </optgroup>
                }
                @if (extra(); as stored) {
                    <optgroup [label]="unknown() ? 'Not known to this Space' : 'Stored'">
                        <option [value]="stored" selected>{{ unknown() ? stored + ' (unknown)' : stored }}</option>
                    </optgroup>
                }
            </select>
        }
    `,
})
export class InspectoClassificationSelectComponent {
    protected readonly vocabulary = inject(ClassificationsService);

    /** The stored classification; blank is "none". */
    readonly value = model<string>('');
    /** The accessible name, when no wrapping `<label>` gives one. */
    readonly label = input('');
    readonly disabled = input(false);

    readonly masking = computed(() => this.vocabulary.items().filter((c) => c.masked));
    readonly plain = computed(() => this.vocabulary.items().filter((c) => !c.masked));

    /** The known class the value is, compared as the server compares (trimmed, any case). */
    readonly match = computed<Classification | undefined>(() => {
        const v = (this.value() ?? '').trim().toUpperCase();
        return v ? this.vocabulary.items().find((c) => c.class === v) : undefined;
    });
    /** A stored value that is not in the list (yet), kept as its own option rather than dropped. */
    readonly extra = computed(() => {
        const v = (this.value() ?? '').trim();
        return v && !this.match() ? v : null;
    });
    /** Flagged only once the list is read: while it loads every stored value is merely "not listed yet". */
    readonly unknown = computed(() => this.vocabulary.status() === 'ready' && !!this.extra());

    constructor() {
        this.vocabulary.ensureLoaded();
    }

    optionLabel(c: Classification): string {
        const named = c.label && c.label.toUpperCase() !== c.class ? ` · ${c.label}` : '';
        return `${c.class}${named}${c.masked ? ' (masks)' : ''}`;
    }

    pick(v: string): void {
        this.value.set(v);
    }
}
