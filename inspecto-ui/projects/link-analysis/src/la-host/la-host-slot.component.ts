import {
    ChangeDetectionStrategy,
    Component,
    ComponentRef,
    DestroyRef,
    DoCheck,
    Type,
    ViewContainerRef,
    inject,
    input,
} from '@angular/core';

/**
 * Renders a HOST-provided component (a `Type` from an `inspecto/la-host` token) with its inputs and outputs wired,
 * which `NgComponentOutlet` cannot do for outputs. The host component is created as the slot's sibling and the slot
 * is `display: contents`, so layout is the same as if the component had been written in the template directly.
 *
 * `inputs` are applied in `ngDoCheck` (not in an effect), i.e. at the same point a direct template binding would be,
 * and only when a value changed by identity. `outputs` maps an output name to a handler, read at emit time.
 */
@Component({
    selector: 'inspecto-la-host-slot',
    changeDetection: ChangeDetectionStrategy.OnPush,
    host: { style: 'display: contents' },
    template: '',
})
export class LaHostSlotComponent implements DoCheck {
    readonly component = input.required<Type<unknown>>();
    readonly inputs = input<Record<string, unknown>>({});
    readonly outputs = input<Record<string, (value: never) => void>>({});

    private readonly vcr = inject(ViewContainerRef);
    private ref: ComponentRef<unknown> | undefined;
    private type: Type<unknown> | undefined;
    private applied = new Map<string, unknown>();

    constructor() {
        inject(DestroyRef).onDestroy(() => this.ref?.destroy());
    }

    ngDoCheck(): void {
        const type = this.component();
        if (type !== this.type) {
            this.ref?.destroy();
            this.type = type;
            this.applied = new Map();
            const ref = (this.ref = this.vcr.createComponent(type));
            for (const name of Object.keys(this.outputs())) {
                const out = (ref.instance as Record<string, unknown>)[name] as
                    | { subscribe(fn: (value: never) => void): unknown }
                    | undefined;
                out?.subscribe((value: never) => this.outputs()[name]?.(value));
            }
        }
        for (const [name, value] of Object.entries(this.inputs())) {
            if (this.applied.has(name) && Object.is(this.applied.get(name), value)) continue;
            this.applied.set(name, value);
            this.ref?.setInput(name, value);
        }
    }
}
