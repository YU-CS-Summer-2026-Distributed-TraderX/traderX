#!/usr/bin/env python3
"""Render a bounded one-task pilot definition. Does not submit a cloud job."""
import argparse
import json
import re


def plan(image, manifest_uri, generation, digest, service_account, project='project-compsci-484414',
         destination='project-compsci-484414-taq-parquet', campaign=False, object_count=1, parallelism=1):
    if not 1 <= parallelism <= min(object_count, 32) or object_count < 1:
        raise ValueError('require positive task count and at most 32 parallel workers')
    if campaign and (object_count != 1 or parallelism != 1):
        raise ValueError('campaign cannot be combined with array tasks')
    if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}', image):
        raise ValueError('worker image must be pinned by digest')
    if not manifest_uri.startswith('gs://') or int(generation) <= 0 or not re.fullmatch('[a-f0-9]{64}', digest):
        raise ValueError('manifest requires URI, generation and SHA256')
    env = {'WORKER_IMAGE': image, 'GOOGLE_CLOUD_PROJECT': project, 'DESTINATION_BUCKET': destination,
           'INPUT_MANIFEST_URI': manifest_uri, 'INPUT_MANIFEST_GENERATION': str(generation),
           'INPUT_MANIFEST_OBJECT_COUNT': '1', 'INPUT_MANIFEST_SHA256': digest, 'SCRATCH_DIR': '/scratch', 'DUCKDB_MEMORY_LIMIT': '4GB'}
    job = {'taskGroups': [{'taskCount': 1, 'parallelism': 1, 'taskSpec': {
        'runnables': [{'container': {'imageUri': image, 'volumes': ['/mnt/disks/ri30:/scratch']}}],
        'environment': {'variables': env}, 'computeResource': {'cpuMilli': 4000, 'memoryMib': 16384},
        'maxRetryCount': 0, 'maxRunDuration': '7200s',
        'volumes': [{'deviceName': 'ri30', 'mountPath': '/mnt/disks/ri30'}]}}],
        'allocationPolicy': {'instances': [{'policy': {'machineType': 'e2-standard-4',
            'provisioningModel': 'STANDARD', 'disks': [{'deviceName': 'ri30',
                'newDisk': {'sizeGb': 100, 'type': 'pd-balanced'}}]}}],
            'location': {'allowedLocations': ['regions/us-east1']},
            'serviceAccount': {'email': service_account}},
        'logsPolicy': {'destination': 'CLOUD_LOGGING'}, 'labels': {'task': 'ri30', 'phase': 'pilot'}}
    if campaign:
        spec=job['taskGroups'][0]['taskSpec']
        spec['environment']['variables']['INPUT_MANIFEST_OBJECT_COUNT']='3'
        spec['runnables'][0]['container'].update(entrypoint='python', commands=['/app/campaign.py'])
        spec['maxRunDuration']='28800s'
        job['allocationPolicy']['instances'][0]['policy']['disks'][0]['newDisk']['sizeGb']=200
    elif object_count > 1:
        group = job['taskGroups'][0]
        group.update(taskCount=object_count, parallelism=parallelism, taskCountPerNode=1)
        group['taskSpec']['environment']['variables']['INPUT_MANIFEST_OBJECT_COUNT']=str(object_count)
        group['taskSpec']['maxRunDuration']='43200s'
        job['labels']['phase']='full'
        job['allocationPolicy']['labels']={'task':'ri30','phase':'full'}
    return job


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('image', 'manifest-uri', 'generation', 'digest', 'service-account'):
        p.add_argument('--' + name, required=True)
    p.add_argument('--three-archive-campaign', action='store_true')
    p.add_argument('--object-count', type=int, default=1)
    p.add_argument('--parallelism', type=int, default=1)
    a = p.parse_args()
    print(json.dumps(plan(a.image, a.manifest_uri, a.generation, a.digest, a.service_account, campaign=a.three_archive_campaign,
                          object_count=a.object_count, parallelism=a.parallelism), indent=2))
