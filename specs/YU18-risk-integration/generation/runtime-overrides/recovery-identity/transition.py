"""Resume an explicit LOCAL fresh-run transition from its durable SQL phase.

Provision/start old and fresh members separately. This tool neither starts a rig,
changes storage, wipes history, nor derives identity from process time.
"""
import argparse
import json
import os
from pathlib import Path
from urllib.parse import urlsplit
from urllib.request import Request, build_opener, HTTPRedirectHandler, ProxyHandler
from urllib.error import HTTPError
import provision

NEXT = {'PREPARED': 'freeze', 'FROZEN': 'verify', 'VERIFIED': 'select', 'SELECTED': 'activate'}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Controller:
    def __init__(self, endpoint, token, authorization=None):
        parsed = urlsplit(endpoint)
        if parsed.scheme != 'http' or parsed.hostname not in ('localhost', '127.0.0.1', '::1') or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path not in ('', '/'):
            raise ValueError('local loopback HTTP controller endpoint required')
        if not token:
            raise ValueError('risk-control token environment variable is required')
        self.endpoint = endpoint.rstrip('/') + '/v2/projection-control'
        self.opener = build_opener(ProxyHandler({}), NoRedirect())
        self.headers = {'X-Risk-Control-Token': token, 'X-Risk-Operator': 'local-run-migration', 'Content-Type': 'application/json'}
        if authorization:
            self.headers['Authorization'] = authorization

    def call(self, action, payload=None):
        req = Request(self.endpoint + '/' + action, headers=self.headers,
                      data=json.dumps(payload).encode() if payload is not None else None,
                      method='POST' if payload is not None else 'GET')
        try:
            with self.opener.open(req, timeout=180) as response:
                return provision.parse(response.read())
        except HTTPError as error:
            # Status is safe to report; do not echo tokens or a server's financial witness.
            if error.code == 404 and action.startswith('state/'):
                return None
            raise RuntimeError(f'migration action {action} refused: HTTP {error.code}') from None


def resume(controller, transition_id, old_scope, raw_descriptor, old_endpoint, new_endpoint):
    if not provision.re.fullmatch(r'[a-z0-9_-]{1,64}', transition_id):
        raise ValueError('invalid transition id')
    d = provision.descriptor(raw_descriptor)
    if d['eventIdScheme'] != 'epoch-v1':
        raise ValueError('fresh transition requires an epoch-v1 descriptor')
    payload = dict(transitionId=transition_id, oldScope=old_scope, descriptorJson=raw_descriptor.decode('utf-8'),
                   oldEndpoint=old_endpoint, newEndpoint=new_endpoint)
    state = controller.call('state/' + transition_id)
    if state is None or state['phase'] == 'PREPARED':
        state = controller.call('prepare', payload)  # retries a declare interrupted after SQL prepare
    for _ in range(5):
        if state['old_scope'] != old_scope or state['new_scope'] != d['projectionScope'] or state['descriptor_hash'] != provision.sha(raw_descriptor):
            raise ValueError('durable transition intent differs from supplied descriptor/scopes')
        if state['phase'] == 'COMPLETE':
            return state
        action = NEXT.get(state['phase'])
        if action is None:
            raise ValueError('unknown durable transition phase')
        state = controller.call(action, payload)
    raise RuntimeError('transition did not reach COMPLETE; rerun using the same immutable intent')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('adopt-legacy', 'run', 'status'))
    parser.add_argument('--controller', required=True)
    parser.add_argument('--token-env', default='RISK_CONTROL_TOKEN')
    parser.add_argument('--authorization-env', default='RUN_MIGRATION_AUTHORIZATION')
    parser.add_argument('--descriptor')
    parser.add_argument('--transition-id')
    parser.add_argument('--old-scope')
    parser.add_argument('--old-endpoint')
    parser.add_argument('--new-endpoint')
    a = parser.parse_args()
    controller = Controller(a.controller, os.environ.get(a.token_env), os.environ.get(a.authorization_env))
    if a.operation == 'status':
        if not a.transition_id or not provision.re.fullmatch(r'[a-z0-9_-]{1,64}', a.transition_id):
            parser.error('status requires a valid --transition-id')
        result = controller.call('state/' + a.transition_id)
    elif a.operation == 'adopt-legacy':
        if not a.descriptor or not a.old_endpoint:
            parser.error('adopt-legacy requires --descriptor and --old-endpoint')
        raw = provision.read_regular(Path(a.descriptor))
        if provision.descriptor(raw)['eventIdScheme'] != 'legacy-v0':
            parser.error('adopt-legacy requires an evidenced legacy-v0 descriptor')
        result = controller.call('adopt-legacy', {'descriptorJson': raw.decode('utf-8'), 'oldEndpoint': a.old_endpoint})
    else:
        if not all((a.descriptor, a.transition_id, a.old_scope, a.old_endpoint, a.new_endpoint)):
            parser.error('run requires descriptor, transition id, old scope, and both gateway endpoints')
        result = resume(controller, a.transition_id, a.old_scope, provision.read_regular(a.descriptor), a.old_endpoint, a.new_endpoint)
    print(json.dumps(result, indent=2, sort_keys=True))


if __name__ == '__main__':
    main()
