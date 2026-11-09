"""Safe output for the tools/gen-la-*-data.py showcase generators (LA-GENERATOR-DELETE-FIRST-1).

The generators used to delete every committed CSV and then rewrite it. On Windows a just-written file is often held for a
moment by another process (a file watcher, the virus scanner), so a second run failed while deleting and left committed
files gone. Here each file is built in memory, left alone when its bytes are unchanged, otherwise written to a temporary
name beside it and moved onto the final name in one step; and only the files a run no longer writes are removed, after
every write succeeded. A PermissionError (WinError 5 or 32) on a move or a removal is retried a few times.

    out = Outputs()
    with out.open(path) as fh:      # a text buffer; the file is written when the block ends
        csv.writer(fh, lineterminator="\\n").writerows(rows)
    out.write_text(TRUTH, text)
    out.prune(DATA, "*/*/*.csv")    # last: removes the matching files this run did not write
"""
from __future__ import annotations

import contextlib
import io
import os
import time
from pathlib import Path

TRIES = 10
PAUSE = 0.2


def retry(op):
    """Run op(), retrying a PermissionError (a file briefly held by another process on Windows) up to TRIES times."""
    for attempt in range(TRIES):
        try:
            return op()
        except PermissionError:
            if attempt == TRIES - 1:
                raise
            time.sleep(PAUSE)


class Outputs:
    def __init__(self):
        self.written = set()

    def put(self, path, data: bytes) -> bool:
        """Make path hold exactly data. Returns True when the file was (re)written, False when it already held data."""
        path = Path(path)
        self.written.add(path.resolve())
        try:
            if retry(path.read_bytes) == data:
                return False
        except FileNotFoundError:
            pass
        tmp = path.with_name(f".{path.name}.{os.getpid()}.tmp")
        try:
            tmp.write_bytes(data)
            retry(lambda: os.replace(tmp, path))
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
        return True

    def write_text(self, path, text: str) -> bool:
        """Like Path.write_text(text, encoding="utf-8", newline="\\n")."""
        return self.put(path, text.encode("utf-8"))

    @contextlib.contextmanager
    def open(self, path):
        """Like path.open("w", newline="", encoding="utf-8"), but the file is written only when the block ends cleanly."""
        buf = io.StringIO(newline="")
        yield buf
        self.write_text(path, buf.getvalue())

    def prune(self, root, pattern) -> list:
        """Remove the files under root matching pattern that this run did not write. Call it after every write."""
        stale = [p for p in sorted(Path(root).glob(pattern)) if p.resolve() not in self.written]
        for p in stale:
            retry(lambda: p.unlink(missing_ok=True))
        return stale
