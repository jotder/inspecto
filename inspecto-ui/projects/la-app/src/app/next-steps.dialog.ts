import { ChangeDetectionStrategy, Component, computed, inject, OnInit, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { Router } from '@angular/router';
import { InspectoSkeletonComponent } from '@inspecto/core/components/skeleton.component';
import { LockedActionComponent } from '@inspecto/core/components/locked-action.component';
import { SpaceGuideComponent } from '@inspecto/core/onboarding/space-guide.component';
import { SpaceGuideState, SpaceGuideStepId } from '@inspecto/core/onboarding/space-guide';
import { SpaceGuideService } from '@inspecto/core/onboarding/space-guide.service';
import { AddDataAccess } from '@inspecto/link-analysis/add-data/add-data-access';
import { FirstRun } from './first-run';
import { NextStep, nextSteps } from './next-steps';

/**
 * **What can I do next?** (LA-ONBOARD-1 phase 8, UC-15 rule 4), opened from the navigation rail on every screen: the
 * Space guide (H1) while it is not done, and the next actions read from the server's state of the current Space - the
 * recommended one first, each with one line of why; one this person may not take is shown locked with the reason.
 */
@Component({
    selector: 'la-app-next-steps-dialog',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [MatButtonModule, MatDialogModule, InspectoSkeletonComponent, LockedActionComponent, SpaceGuideComponent],
    template: `
        <h2 mat-dialog-title>What can I do next?</h2>
        <mat-dialog-content class="flex flex-col gap-4">
            @if (state(); as s) {
                <ol class="m-0 flex list-none flex-col gap-3 p-0" data-testid="la-next-steps">
                    @for (step of steps(); track step.id; let first = $first) {
                        <li class="flex flex-col items-start gap-1" [attr.data-step]="step.id">
                            @if (step.locked) {
                                <inspecto-locked-action [label]="step.label" [reason]="step.locked" />
                            } @else if (first) {
                                <button mat-flat-button color="primary" type="button" (click)="go(step)">
                                    {{ step.label }}
                                </button>
                            } @else {
                                <button mat-stroked-button type="button" (click)="go(step)">{{ step.label }}</button>
                            }
                            <p class="text-secondary m-0 text-sm">{{ step.why }}</p>
                        </li>
                    }
                </ol>
                <inspecto-space-guide [state]="s" (stepOpen)="openStep($event)"></inspecto-space-guide>
            } @else {
                <inspecto-skeleton [lines]="3"></inspecto-skeleton>
            }
        </mat-dialog-content>
        <mat-dialog-actions align="end">
            <button mat-button type="button" mat-dialog-close>Close</button>
        </mat-dialog-actions>
    `,
})
export class NextStepsDialog implements OnInit {
    private readonly guide = inject(SpaceGuideService);
    private readonly access = inject(AddDataAccess);
    private readonly firstRun = inject(FirstRun);
    private readonly router = inject(Router);
    private readonly ref = inject(MatDialogRef<NextStepsDialog>);

    /** The current Space's state from the server; null while it loads. */
    protected readonly state = signal<SpaceGuideState | null>(null);
    protected readonly steps = computed(() => {
        const s = this.state();
        return s
            ? nextSteps(s, { canCreateSpace: this.firstRun.canCreate(), addDataLock: this.access.lockReason() })
            : [];
    });

    ngOnInit(): void {
        this.access.load();
        this.guide.load().subscribe((s) => this.state.set(s));
    }

    protected go(step: NextStep): void {
        this.ref.close();
        if (step.to === 'create-space') this.firstRun.createSpace();
        else void this.router.navigateByUrl(step.to);
    }

    protected openStep(id: SpaceGuideStepId): void {
        this.ref.close();
        this.firstRun.openStep(id);
    }
}
