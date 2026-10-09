#!/usr/bin/env python3
"""Render cluster-tier scrapes from declared Services, without probing away failures.

A declared but unhealthy service stays a target. Optional absent services do not create
permanent errors. JSON is valid YAML for both the ConfigMap and prometheus.yml.
"""
import argparse
import json
import re
from pathlib import Path


def render(services, namespace, members):
    if not re.fullmatch(r'[a-z0-9]([-a-z0-9]*[a-z0-9])?', namespace) or not 1 <= members <= 9:
        raise ValueError('Explicit namespace and 1..9 members required')
    selected = {s['metadata']['name']: s for s in services['items']
                if s['metadata'].get('namespace', namespace) == namespace}
    gateways = [n for n in ('order-matcher', 'order-matcher-gw')
                if n in selected and selected[n].get('spec', {}).get('selector', {}).get('app') == 'cluster-gateway']
    if not gateways:
        raise ValueError('No declared cluster gateway service')
    def job(name, targets, path='/metrics'):
        return {'job_name': name, 'metrics_path': path, 'static_configs': [{'targets': targets}]}
    jobs = [job('prometheus', ['prometheus:9090'], '/prometheus/metrics'),
            job('otel-collector', ['otel-collector:8888', 'otel-collector:8889'])]
    spring = {'account-service': 18088, 'position-service': 18090,
              'trade-processor': 18091, 'trade-service': 18092}
    targets = [f'{name}:{port}' for name, port in spring.items() if name in selected]
    if targets:
        jobs.append(job('traderx-spring-boot-actuator', targets, '/actuator/prometheus'))
    jobs.append(job('traderx-cluster-gateway', [gateways[0] + ':18110']))
    member_job = job('traderx-cluster-members', [
        f'order-matcher-cluster-{i}.order-matcher-cluster.{namespace}.svc.cluster.local:8080'
        for i in range(members)])
    member_job['relabel_configs'] = [{'source_labels': ['__address__'],
        'regex': r'order-matcher-cluster-([0-9]+)\..*', 'target_label': 'member', 'replacement': '$1'}]
    jobs.append(member_job)
    config = {'global': {'scrape_interval': '15s', 'evaluation_interval': '15s'}, 'scrape_configs': jobs}
    return {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {
        'name': 'observability-prometheus-config', 'namespace': namespace},
        'data': {'prometheus.yml': json.dumps(config, indent=2)}}


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--services', type=Path, required=True)
    p.add_argument('--namespace', required=True)
    p.add_argument('--members', type=int, required=True)
    p.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    a.output.write_text(json.dumps(render(json.loads(a.services.read_text()), a.namespace, a.members), indent=2) + '\n')
