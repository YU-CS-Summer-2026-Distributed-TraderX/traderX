#!/usr/bin/env python3
"""Render separately authorized, source-restricted CompSci frontend exposure."""
import argparse
import ipaddress
import json

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--address', required=True, help='Reserved regional external IPv4 address')
p.add_argument('--source', required=True, help='Allowed public IPv4 address (one /32)')
a = p.parse_args()
address = ipaddress.IPv4Address(a.address)
source = ipaddress.IPv4Address(a.source)
if not address.is_global or not source.is_global:
    p.error('address and source must be public IPv4 addresses; broad ranges are not accepted')
print(json.dumps({
    'apiVersion': 'v1', 'kind': 'Service',
    'metadata': {'name': 'console-external', 'namespace': 'traderx',
                 'annotations': {'cloud.google.com/l4-rbs': 'enabled'}},
    'spec': {'type': 'LoadBalancer', 'loadBalancerIP': str(address),
             'loadBalancerSourceRanges': [str(source) + '/32'],
             'externalTrafficPolicy': 'Local',
             'selector': {'app': 'web-front-end-console'},
             'ports': [{'name': 'http', 'protocol': 'TCP', 'port': 80, 'targetPort': 8080}]}
}, indent=2))
