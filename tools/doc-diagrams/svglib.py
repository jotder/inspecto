"""Shared helpers for the solution-suite diagrams (SOLUTION-SUITE-DOCS-1).

Each page has its own script next to this file, e.g. ``maker-checker.py``, which imports this module and
writes one light-theme SVG. Run: ``python tools/doc-diagrams/<page>.py`` from the repo root.
Palette roles: purple = add-on / governance, teal = platform base, gray = actor / neutral,
coral = mutating / risk, blue = UI / API.
"""
import os

C = {'purple': ('#EEEDFE', '#534AB7', '#3C3489', '#534AB7'),
     'teal': ('#E1F5EE', '#0F6E56', '#085041', '#0F6E56'),
     'gray': ('#F1EFE8', '#5F5E5A', '#444441', '#5F5E5A'),
     'coral': ('#FAECE7', '#993C1D', '#712B13', '#993C1D'),
     'blue': ('#E6F1FB', '#185FA5', '#0C447C', '#185FA5')}
FONT = 'font-family="system-ui, -apple-system, Segoe UI, sans-serif"'
MARK = ('<defs><marker id="a" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" '
        'orient="auto-start-reverse"><path d="M2 1L8 5L2 9" fill="none" stroke="#888780" stroke-width="1.5" '
        'stroke-linecap="round" stroke-linejoin="round"/></marker></defs>')


def esc(s):
    return s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


class Svg:
    """Fixed-width canvas; boxes take a title and an optional multi-line subtitle (\n separated)."""

    def __init__(self, w, h, title, desc):
        self.w, self.h = w, h
        self.o = [f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" viewBox="0 0 {w} {h}" role="img" {FONT}>',
                  f'<title>{esc(title)}</title><desc>{esc(desc)}</desc>', MARK,
                  f'<rect x="0" y="0" width="{w}" height="{h}" fill="#FFFFFF"/>']

    def box(self, x, y, w, h, color, title, sub=None):
        f, st, tc, sc = C[color]
        cx = x + w / 2
        self.o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="{f}" stroke="{st}" stroke-width="0.6"/>')
        ty = y + 19 if sub else y + h / 2 + 5
        self.o.append(f'<text x="{cx:g}" y="{ty:g}" text-anchor="middle" font-size="13" font-weight="500" fill="{tc}">{esc(title)}</text>')
        for i, line in enumerate((sub or '').split('\n') if sub else []):
            self.o.append(f'<text x="{cx:g}" y="{y + 35 + 14 * i}" text-anchor="middle" font-size="11" fill="{sc}">{esc(line)}</text>')

    def group(self, x, y, w, h, label):
        self.o.append(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="4" fill="none" stroke="#B4B2A9" stroke-width="0.6" stroke-dasharray="4 3"/>')
        self.o.append(f'<text x="{x + 8}" y="{y + 15}" font-size="11" fill="#5F5E5A">{esc(label)}</text>')

    def arrow(self, x1, y1, x2, y2, dash=False, label=None):
        d = ' stroke-dasharray="4 3"' if dash else ''
        self.o.append(f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="#888780" stroke-width="1"{d} marker-end="url(#a)"/>')
        if label:
            self.text((x1 + x2) / 2 + 4, (y1 + y2) / 2 - 4, label, size=10)

    def path(self, d, dash=False):
        dd = ' stroke-dasharray="4 3"' if dash else ''
        self.o.append(f'<path d="{d}" fill="none" stroke="#888780" stroke-width="1"{dd} marker-end="url(#a)"/>')

    def text(self, x, y, t, anchor='start', size=11, color='#5F5E5A'):
        self.o.append(f'<text x="{x:g}" y="{y:g}" text-anchor="{anchor}" font-size="{size}" fill="{color}">{esc(t)}</text>')

    def save(self, rel_path):
        """rel_path is relative to the repo root, e.g. docs/okf/product/assets/x.svg."""
        self.o.append('</svg>')
        os.makedirs(os.path.dirname(rel_path), exist_ok=True)
        with open(rel_path, 'w', encoding='utf-8') as fh:
            fh.write('\n'.join(self.o) + '\n')
