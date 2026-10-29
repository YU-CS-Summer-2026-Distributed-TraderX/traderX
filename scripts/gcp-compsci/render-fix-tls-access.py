#!/usr/bin/env python3
"""ADR-095: render the separately authorized external L4 entry to the gateways' FIX TLS port.

A regional external passthrough Network LB of its own (Service `fix-tls-external`), independent of the internal
LB in-region clients use. loadBalancerSourceRanges becomes the VPC firewall rule: only the listed /32s reach the
port. externalTrafficPolicy Local keeps the client's address for that check and for the gateway log.
"""
import argparse
import ipaddress
import json

FIX_TLS_PORT = 18143

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--address', help='Reserved regional external IPv4 address (omit on kind)')
p.add_argument('--source', required=True, action='append',
               help='Allowed client IPv4 address, one /32 each; repeat for more. Never a range.')
p.add_argument('--namespace', default='traderx')
p.add_argument('--allow-private', action='store_true', help='kind proof only: accept non-public addresses')
a = p.parse_args()
try:
    sources = [ipaddress.IPv4Address(s) for s in a.source]
    addresses = sources + ([ipaddress.IPv4Address(a.address)] if a.address else [])
except ipaddress.AddressValueError as error:
    p.error(f'single IPv4 addresses only, never a range: {error}')
if not a.allow_private and (not a.address or not all(x.is_global for x in addresses)):
    p.error('--address and every --source must be public IPv4 addresses; broad ranges are not accepted')
spec = {'type': 'LoadBalancer',
        'loadBalancerSourceRanges': [f'{s}/32' for s in sources],
        'externalTrafficPolicy': 'Local',
        # One client keeps one gateway; a move is still safe, ADR-086 resumes the session on any replica.
        'sessionAffinity': 'ClientIP',
        'selector': {'app': 'cluster-gateway'},
        'ports': [{'name': 'fix-tls', 'protocol': 'TCP', 'port': FIX_TLS_PORT, 'targetPort': FIX_TLS_PORT}]}
if a.address:
    spec['loadBalancerIP'] = a.address
print(json.dumps({
    'apiVersion': 'v1', 'kind': 'Service',
    'metadata': {'name': 'fix-tls-external', 'namespace': a.namespace,
                 'annotations': {'cloud.google.com/l4-rbs': 'enabled'}},
    'spec': spec}, indent=2))
