import { PickerOption } from 'app/inspecto/components/option-picker.component';

/**
 * The Disposition ladder (GLOSSARY §9) — the decided outcome an Incident resolves with, and the built-in
 * choices of a Case's Findings. Mirrors the backend `FindingsSpec.DISPOSITIONS`, which is what the server
 * enforces: resolving an Incident without one of these is a 422 (WS-10, `ASSURE-IMPACT-LEDGER-1`). The
 * wire value is the enum spelling; the label is what the operator reads.
 */
export const DISPOSITIONS: readonly PickerOption[] = [
    { value: 'CONFIRMED', label: 'Confirmed', hint: 'A real problem with a real loss — recovery may be pending.' },
    { value: 'FALSE_POSITIVE', label: 'False positive', hint: 'Not a problem after all — no loss.' },
    { value: 'RECOVERED', label: 'Recovered', hint: 'A real loss, and the value has been recovered.' },
    { value: 'WRITTEN_OFF', label: 'Written off', hint: 'A real loss that will not be recovered.' },
    { value: 'INCONCLUSIVE', label: 'Inconclusive', hint: 'The investigation could not decide.' },
    { value: 'DUPLICATE', label: 'Duplicate', hint: 'The same problem is tracked by another Incident or Case.' },
    { value: 'ACCEPTED_RISK', label: 'Accepted risk', hint: 'Real, and knowingly left in place.' },
];

/** The operator-facing label of a stored Disposition; an unknown value is shown verbatim. */
export function dispositionLabel(value: string | null | undefined): string {
    if (!value) return '';
    return DISPOSITIONS.find((d) => d.value === value)?.label ?? value;
}
