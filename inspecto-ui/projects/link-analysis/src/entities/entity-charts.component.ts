import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';
import { Bar, CalendarWeek, calendarRows, count } from './entity-profile-view';

/** An accessible relative bar list: each bar is a share of the largest item, and its text says so. */
@Component({
    selector: 'inspecto-la-bar-list',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (bars().length) {
            <ul class="m-0 flex list-none flex-col gap-1 p-0" [attr.aria-label]="label()">
                @for (b of bars(); track b.label) {
                    <li class="grid grid-cols-[6rem_1fr_5rem] items-center gap-2 text-sm" data-testid="bar">
                        <span class="truncate tabular-nums">{{ b.label }}</span>
                        <span class="bg-hover block h-2 rounded" aria-hidden="true">
                            <span class="bg-primary block h-2 rounded" [style.width.%]="b.pct"></span>
                        </span>
                        <span class="text-right tabular-nums" aria-hidden="true">{{ count(b.rows) }}</span>
                        <span class="sr-only">{{ b.srText }}</span>
                    </li>
                }
            </ul>
        } @else {
            <p class="text-secondary m-0 text-sm">{{ empty() }}</p>
        }
    `,
})
export class EntityBarListComponent {
    readonly bars = input.required<Bar[]>();
    readonly label = input.required<string>();
    readonly empty = input('Nothing to show.');
    protected readonly count = count;
}

/** The per-day calendar as a real table: one row per weekday, one column per week; every cell names its day in words. */
@Component({
    selector: 'inspecto-la-day-calendar',
    standalone: true,
    changeDetection: ChangeDetectionStrategy.OnPush,
    template: `
        @if (weeks().length) {
            <div class="overflow-x-auto">
                <table class="border-separate border-spacing-0.5 text-xs" data-testid="calendar">
                    <caption class="sr-only">
                        {{
                            caption()
                        }}
                    </caption>
                    <thead>
                        <tr>
                            <th scope="col"><span class="sr-only">Weekday</span></th>
                            @for (w of weeks(); track w.start) {
                                <th scope="col" class="text-secondary font-normal">
                                    <span class="sr-only">Week of {{ w.start }}</span>
                                </th>
                            }
                        </tr>
                    </thead>
                    <tbody>
                        @for (r of rows(); track r.weekday) {
                            <tr>
                                <th scope="row" class="text-secondary pr-1 text-left font-normal">{{ r.weekday }}</th>
                                @for (c of r.cells; track $index) {
                                    @if (c) {
                                        <td
                                            class="h-3 w-3 rounded-sm border"
                                            [class.border-dashed]="c.missing"
                                            [style.background]="c.background"
                                            [attr.title]="c.text"
                                            [attr.data-day]="c.date"
                                        >
                                            <span class="sr-only">{{ c.text }}</span>
                                        </td>
                                    } @else {
                                        <td class="h-3 w-3"></td>
                                    }
                                }
                            </tr>
                        }
                    </tbody>
                </table>
            </div>
        } @else {
            <p class="text-secondary m-0 text-sm">No day was read.</p>
        }
    `,
})
export class EntityDayCalendarComponent {
    readonly weeks = input.required<CalendarWeek[]>();
    readonly caption = input('Rows per day');
    protected readonly rows = computed(() => calendarRows(this.weeks()));
}
