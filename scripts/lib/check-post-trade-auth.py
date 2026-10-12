#!/usr/bin/env python3
"""Validate the active generated reconciliation properties, without printing secret values."""
import re
import sys
from pathlib import Path


def check(path):
    values = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith(('#', '!')):
            continue
        key, sep, value = line.partition('=')
        if sep:
            key = key.strip()
            if key in values:
                raise ValueError(f'duplicate active property: {key}')
            values[key] = value.strip()
    for key in ('settlement.t-plus-days', 'order-matcher.base-url',
                'auth.jwt.secret', 'auth.dev-token.master-secret'):
        if not values.get(key):
            raise ValueError(f'missing active post-trade property: {key}')
    for key, variable in [('auth.jwt.secret', 'AUTH_JWT_SECRET'),
                          ('auth.dev-token.master-secret', 'AUTH_DEV_TOKEN_MASTER_SECRET')]:
        if not re.fullmatch(r'\$\{' + variable + r'(?::[^}]+)?\}', values[key]):
            raise ValueError(f'{key} must use the {variable} environment binding')


if __name__ == '__main__':
    try:
        check(Path(sys.argv[1]))
    except (IndexError, OSError, ValueError) as error:
        print(f'[error] {error}', file=sys.stderr)
        sys.exit(1)
    print('[ok] active JWT reconciliation configuration verified')
