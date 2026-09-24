"""Single-host receipt-directory lineage in retained inbox custody, not epoch issuance."""
import os
from pathlib import Path
import sqlite3

import bundle


STORE = '.receipt-scopes.sqlite3'


def bind(receipt_directory, inbox, epoch):
    """Commit before publication; never rebind, even after interrupted publication.

    The first epoch is an operator assertion. Preserve this database with the inbox.
    Separate inboxes/copies of producer directories are separate custody domains.
    Call only after validating receipt, artifacts and the resulting manifest.
    """
    scope = str(Path(receipt_directory).resolve(strict=True))
    bundle.require(isinstance(epoch, str) and bool(epoch.strip()), 'invalid receipt epoch')
    path = Path(inbox) / STORE
    bundle.require(not path.is_symlink(), 'receipt scope database must not be a symlink')
    fd = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    os.close(fd)
    os.chmod(path, 0o600)
    try:
        db = sqlite3.connect(path, timeout=10)
        try:
            db.execute('PRAGMA synchronous=FULL')
            db.execute('BEGIN IMMEDIATE')
            version = db.execute('PRAGMA user_version').fetchone()[0]
            bundle.require(version in (0, 1), 'unsupported receipt scope database version')
            if version == 0:
                # Only an empty database is eligible for first-use initialization.
                bundle.require(not db.execute(
                    "SELECT name FROM sqlite_master WHERE type='table'").fetchall(),
                    'unrecognized receipt scope database')
                db.execute('CREATE TABLE scopes (directory TEXT PRIMARY KEY, epoch TEXT NOT NULL)')
                db.execute('PRAGMA user_version=1')
            row = db.execute('SELECT epoch FROM scopes WHERE directory=?', (scope,)).fetchone()
            bundle.require(row is None or row[0] == epoch,
                           'receipt directory is bound to a different epoch; refusing relabel')
            if row is None:
                db.execute('INSERT INTO scopes VALUES (?, ?)', (scope, epoch))
            db.commit()
        finally:
            if db.in_transaction:
                db.rollback()
            db.close()
        # Persist the directory entry as well as SQLite's transaction before publishing.
        directory_fd = os.open(Path(inbox), os.O_RDONLY)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    except sqlite3.Error as exc:
        raise ValueError(f'receipt scope database failure: {exc}') from exc
