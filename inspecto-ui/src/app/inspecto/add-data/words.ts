import { formatNumber } from '@inspecto/core/viz/number-format';

/**
 * The one place the Add data screens agree in number (LA-FULL-CYCLE-TEST-1 finding 10): "1 row was", "3 rows were";
 * "1 does not", "3 do not". The server's reasons use the same two helpers in `Words.java`.
 */

/** `one` when `n` is 1, else `many`: `pick(1, 'is', 'are')` is "is". */
export function pick(n: number, one: string, many: string): string {
    return n === 1 ? one : many;
}

/** The count with its words: "1 row", "1,200 rows" (thousands separators through the one number formatter). */
export function plural(n: number, one: string, many: string): string {
    return `${formatNumber(n, { decimals: 0 })} ${pick(n, one, many)}`;
}
