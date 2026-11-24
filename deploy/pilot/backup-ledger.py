#!/usr/bin/env python3
"""Offline SQLite snapshot and isolated restore drill; never overwrites a database."""
import argparse
import hashlib
import os
from pathlib import Path
import sqlite3
import stat
import tempfile
from contextlib import closing

REQUIRED = {'shops', 'members', 'clients', 'entries'}


def open_readonly(path):
    return sqlite3.connect(path.resolve(strict=True).as_uri() + '?mode=ro', uri=True, timeout=10)


def inspect(path):
    with closing(open_readonly(path)) as db:
        if db.execute('PRAGMA integrity_check').fetchall() != [('ok',)]:
            raise ValueError('SQLite integrity check failed')
        if db.execute('PRAGMA foreign_key_check').fetchone() is not None:
            raise ValueError('Foreign key check failed')
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not REQUIRED <= tables:
            raise ValueError('Not a Raschet ledger database')
        digest = hashlib.sha256()
        for line in db.iterdump():
            digest.update(line.encode('utf-8') + b'\n')
        return digest.hexdigest()


def snapshot(source, destination):
    source = source.resolve(strict=True)
    parent = destination.parent.resolve(strict=True)
    if not parent.is_dir() or stat.S_IMODE(parent.stat().st_mode) & 0o077:
        raise ValueError('Destination directory must be private (0700)')
    destination = parent / destination.name
    if destination.exists() or destination.is_symlink():
        raise FileExistsError('Destination already exists; refusing to overwrite')
    fd, name = tempfile.mkstemp(prefix='.raschet-', suffix='.sqlite', dir=parent)
    os.close(fd)
    temporary = Path(name)
    try:
        with closing(open_readonly(source)) as src, closing(sqlite3.connect(temporary)) as dst:
            src.backup(dst)
            dst.execute('PRAGMA journal_mode=DELETE')
        result = inspect(temporary)
        with temporary.open('rb') as file:
            os.fsync(file.fileno())
        os.link(temporary, destination)  # atomic publish; never replaces an existing file
        dir_fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(dir_fd)
        finally:
            os.close(dir_fd)
        return result
    finally:
        temporary.unlink(missing_ok=True)
        for suffix in ('-wal', '-shm', '-journal'):
            Path(str(temporary) + suffix).unlink(missing_ok=True)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    make = sub.add_parser('backup')
    make.add_argument('source', type=Path)
    make.add_argument('destination', type=Path)
    check = sub.add_parser('restore-check')
    check.add_argument('snapshot', type=Path)
    args = parser.parse_args()
    if args.command == 'backup':
        snapshot(args.source, args.destination)
        print('LEDGER_BACKUP_OK')
    else:
        original = inspect(args.snapshot)
        with tempfile.TemporaryDirectory(prefix='raschet-restore-') as work:
            restored = Path(work) / 'restored.sqlite'
            if snapshot(args.snapshot, restored) != original:
                raise ValueError('Restored logical content differs')
        print('LEDGER_RESTORE_CHECK_OK')


if __name__ == '__main__':
    main()
