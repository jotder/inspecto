/**
 * The Entity Type value shapes (ENTITY-TYPE-CATALOG-1 Q-4), the browser half of `ValueShapes.java`: what one value of a
 * type looks like, so the "what does this column hold" picker can mark a type whose shape the column's sample fits.
 *
 * ⛔ A CLOSED vocabulary — `free`, `e164`, `digits(n)` / `digits(a-b)`, `luhn(n)` / `luhn(a-b)`, `iban`, `email`, `uuid` —
 * never a free regular expression. Every matcher is ASCII only and takes the same steps as the Java one; the shared
 * `entity-shape-parity.fixture.json` feeds both sides, so a value that fits here fits there.
 */

export type ShapeFit = 'none' | 'weak' | 'strong';

/** The shape names, in the order a reader is told them. */
export const VALUE_SHAPE_NAMES = ['free', 'e164', 'digits', 'luhn', 'iban', 'email', 'uuid'] as const;

const SIZED = /^(digits|luhn)\((\d{1,2})(?:-(\d{1,2}))?\)$/;
const MAX_DIGITS = 34;

/** `true` when `shape` is one of the vocabulary. */
export function isValueShape(shape: string): boolean {
    if (['free', 'e164', 'iban', 'email', 'uuid'].includes(shape)) return true;
    const m = SIZED.exec(shape);
    if (!m) return false;
    const a = Number(m[2]);
    const b = m[3] === undefined ? a : Number(m[3]);
    return a >= 1 && a <= b && b <= MAX_DIGITS;
}

/** `true` for a shape that checks nothing (absent or `free`). */
export function isFreeShape(shape: string | null | undefined): boolean {
    return !shape || shape === 'free';
}

/**
 * How specific `shape` is, higher = narrower (as `ValueShapes.specificity`): when two types fit a sample equally, the
 * narrower one ranks first (a Luhn-checked serial over plain digits, plain digits of a set length over a phone number).
 */
export function shapeSpecificity(shape: string | null | undefined): number {
    if (isFreeShape(shape)) return 0;
    if (shape!.startsWith('luhn(')) return 7;
    return ({ iban: 6, email: 5, uuid: 4, e164: 2 } as Record<string, number>)[shape!] ?? 3;
}

/** How `value` fits `shape`; an absent, free or unknown shape fits nothing (it checks nothing). */
export function shapeFit(shape: string | null | undefined, value: string | null | undefined): ShapeFit {
    if (isFreeShape(shape) || value == null || !isValueShape(shape!)) return 'none';
    const v = trimAscii(value);
    switch (shape) {
        case 'e164': {
            const s = strip(v, ' -().');
            if (s.length > 1 && s[0] === '+' && digitsBetween(s.slice(1), 8, 15)) return 'strong';
            return digitsBetween(s, 8, 15) ? 'weak' : 'none';
        }
        case 'iban':
            return isIban(upperAscii(strip(v, ' '))) ? 'strong' : 'none';
        case 'email':
            return isEmail(v) ? 'strong' : 'none';
        case 'uuid':
            return isUuid(v) ? 'strong' : 'none';
        default: {
            const m = SIZED.exec(shape!)!;
            const a = Number(m[2]);
            const b = m[3] === undefined ? a : Number(m[3]);
            if (!digitsBetween(v, a, b)) return 'none';
            // digits alone could be another number of that length: a weak fit, as the Java side
            if (m[1] === 'digits') return 'weak';
            return luhn(v) ? 'strong' : 'none';
        }
    }
}

/**
 * How many of `values` fit `shape` (weak or strong), and whether every non-blank one does: the "shape matches" mark of
 * the picker; `strong` when every one fits strongly (a phone number written with its `+`, not bare digits). Blank values
 * are not counted; a free shape never matches.
 */
export function sampleFits(
    shape: string | null | undefined,
    values: readonly (string | null | undefined)[],
): { fit: number; of: number; all: boolean; strong: boolean } {
    const sample = values.filter((v): v is string => v != null && trimAscii(v) !== '');
    const fits = sample.map((v) => shapeFit(shape, v));
    const fit = fits.filter((f) => f !== 'none').length;
    const all = sample.length > 0 && fit === sample.length;
    return { fit, of: sample.length, all, strong: all && fits.every((f) => f === 'strong') };
}

// ── matchers: ASCII only, the same steps as ValueShapes.java ─────────────────────────────────────────────────────────

const isAsciiSpace = (c: string): boolean => c === ' ' || c === '\t' || c === '\n' || c === '\r';
const isDigit = (c: string): boolean => c >= '0' && c <= '9';
const isHex = (c: string): boolean => isDigit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');

function trimAscii(s: string): string {
    let a = 0;
    let b = s.length;
    while (a < b && isAsciiSpace(s[a])) a++;
    while (b > a && isAsciiSpace(s[b - 1])) b--;
    return s.slice(a, b);
}

function strip(s: string, chars: string): string {
    let out = '';
    for (const c of s) if (!chars.includes(c)) out += c;
    return out;
}

function upperAscii(s: string): string {
    let out = '';
    for (const c of s) out += c >= 'a' && c <= 'z' ? String.fromCharCode(c.charCodeAt(0) - 32) : c;
    return out;
}

function digitsBetween(s: string, min: number, max: number): boolean {
    if (s.length < min || s.length > max) return false;
    for (const c of s) if (!isDigit(c)) return false;
    return true;
}

function luhn(digits: string): boolean {
    let sum = 0;
    let dbl = false;
    for (let i = digits.length - 1; i >= 0; i--) {
        let d = digits.charCodeAt(i) - 48;
        if (dbl) {
            d *= 2;
            if (d > 9) d -= 9;
        }
        sum += d;
        dbl = !dbl;
    }
    return sum % 10 === 0;
}

function isIban(s: string): boolean {
    if (s.length < 15 || s.length > 34) return false;
    for (let i = 0; i < s.length; i++) {
        const c = s[i];
        const letter = c >= 'A' && c <= 'Z';
        if (i < 2 ? !letter : i < 4 ? !isDigit(c) : !(letter || isDigit(c))) return false;
    }
    const r = s.slice(4) + s.slice(0, 4);
    let mod = 0;
    for (const ch of r) {
        const v = isDigit(ch) ? ch.charCodeAt(0) - 48 : ch.charCodeAt(0) - 55;
        mod = v > 9 ? (mod * 100 + v) % 97 : (mod * 10 + v) % 97;
    }
    return mod === 1;
}

function isEmail(s: string): boolean {
    const at = s.indexOf('@');
    if (at <= 0 || at !== s.lastIndexOf('@')) return false;
    for (const c of s) if (isAsciiSpace(c)) return false;
    const domain = s.slice(at + 1);
    const dot = domain.lastIndexOf('.');
    return dot > 0 && dot < domain.length - 1;
}

function isUuid(s: string): boolean {
    if (s.length !== 36) return false;
    for (let i = 0; i < 36; i++) {
        const c = s[i];
        if (i === 8 || i === 13 || i === 18 || i === 23) {
            if (c !== '-') return false;
        } else if (!isHex(c)) return false;
    }
    return true;
}
