import { ChangeDetectionStrategy, Component } from '@angular/core';
import { InspectoEmptyStateComponent } from '@inspecto/core/components/empty-state.component';
import { PRODUCT } from './product.generated';

/**
 * What the shell shows, in place of every page, to a signed-in user whom no Space admits and who may not create one
 * ({@link FirstRun.noAccess}): one plain sentence and who to ask, with nothing to press.
 */
@Component({
    selector: 'la-app-no-access',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    imports: [InspectoEmptyStateComponent],
    template: `
        <section class="mx-auto flex w-full max-w-3xl flex-col gap-6 p-6" aria-labelledby="la-no-access-title">
            <h1 id="la-no-access-title" class="text-title m-0 font-semibold">{{ product }}</h1>
            <inspecto-empty-state
                data-testid="la-no-access"
                icon="heroicons_outline:lock-closed"
                title="You have no access to any Space"
                message="Ask your administrator to give you access to a Space."
            ></inspecto-empty-state>
        </section>
    `,
})
export class NoAccessComponent {
    protected readonly product = PRODUCT.brand.name;
}
