import { Injectable, computed, inject, signal } from '@angular/core';
import { LensService } from '@inspecto/core/api';
import { LA_FEATURES } from '@inspecto/link-analysis/la-host';
import { AUTOMATION_FALLBACK, NO_UPLOAD_RIGHT, REMOTE_FALLBACK, SELF_IMPORT_OFF, editionLock } from './add-data.model';
import { OnboardService } from './onboard.service';

/**
 * Who may **Add data** here, and which edition-withheld choices show locked (LA-ONBOARD-1 OB-7, OB-10; A-11, A-15). One
 * place, so the Add data screen, the Dataset card and both apps' hosts ask the same question. The host's facts (sign-in,
 * edition, `/bootstrap` flags and reasons) arrive through `LA_FEATURES`.
 *
 * - No sign-in: every permission passes (the person is their own administrator); the Space's `data.self_import` still
 *   decides, read from `GET /settings/data` (space-scoped, so a multi-Space install reads the Space in use).
 * - Sign-in: `canUploadData` is needed, and the Space's switch must be on (off by default).
 * The server decides in the end; a refusal it sends (403 with its reason) is adopted through {@link refusedBy}.
 */
@Injectable({ providedIn: 'root' })
export class AddDataAccess {
    private readonly features = inject(LA_FEATURES);
    private readonly lens = inject(LensService);
    private readonly onboard = inject(OnboardService);

    /** The Space's `data.self_import` in force; null until read (or when the server does not say: it then decides at upload). */
    readonly selfImport = signal<boolean | null>(null);
    /** A refusal the server sent while adding data (403 self-import off, or no right): shown as the lock reason. */
    private readonly refused = signal<string | null>(null);
    /** People sign in here: rights are checked. With no sign-in every check passes. */
    private readonly signIn = computed(() => this.features.signIn?.() ?? false);

    /** May upload data: no sign-in, or `canUploadData` (the server gates `/onboard/files` the same way). */
    readonly canUpload = computed(() => !this.signIn() || this.lens.canUploadData());
    /** May change a Pipeline (the console's guided editor): no sign-in, or `canAuthorWorkbench`. */
    readonly canAuthor = computed(() => !this.signIn() || this.lens.canAuthorWorkbench());

    /** Why the whole Add data screen is locked, or null (A-15: never a failing upload). */
    readonly lockReason = computed<string | null>(() => {
        if (!this.canUpload()) return NO_UPLOAD_RIGHT;
        if (this.selfImport() === false) return SELF_IMPORT_OFF;
        return this.refused();
    });

    /** *Read from a database or a remote system*: Standard and above (DT-4). */
    readonly remoteLock = computed(() => this.lock('remoteSystems', REMOTE_FALLBACK));
    /** *Keep this up to date*: Standard and above; Basic is manual (DT-5, DT-14). */
    readonly automationLock = computed(() => this.lock('pipelineAutomation', AUTOMATION_FALLBACK));

    /** Read the Space's switch. A server without the route leaves it unknown (the upload then answers for itself). */
    load(): void {
        this.refused.set(null);
        this.onboard.dataSettings().subscribe({
            next: (s) => this.selfImport.set(s.selfImportInForce !== false),
            error: () => this.selfImport.set(null),
        });
    }

    /** The server refused with this reason (403 at upload): lock the screen with it rather than offer a failing upload. */
    refusedBy(reason: string): void {
        this.refused.set(reason);
    }

    private lock(feature: string, fallback: string): string | null {
        return editionLock(
            feature,
            this.features.flags?.() ?? {},
            this.features.featureReasons?.() ?? {},
            this.features.edition?.() ?? '',
            fallback,
        );
    }
}
