#!/usr/bin/env python3
"""ADR-096 §6: the declared image population of a CompSci candidate, read from one reviewed file.

`population.json` names the source commit every private image is built from (`imageSourceCommit`) and, per
workload, any exception: a workload whose image is built from another reviewed commit, published under its own
repository so the candidate never holds two digests of one image name. Rollout 5a declares one: the members
(`order-matcher-cluster`) run `cluster-node-member` built from the 00b31d33 backport, while the gateway's
`cluster-node` and everything else come from the tip. Nothing else may differ from the default commit.
"""
import json
from pathlib import Path
import re

SCHEMA = 'traderx-compsci-population-v1'
DEFAULT = Path(__file__).resolve().with_name('population.json')
SHA = re.compile(r'[0-9a-f]{40}')
NAME = re.compile(r'[a-z][a-z0-9-]{0,62}')


class Refusal(Exception):
    pass


def require(value, message):
    if not value:
        raise Refusal(message)


def parse(value):
    require(isinstance(value, dict) and value.get('schema') == SCHEMA, 'unsupported population declaration')
    require(set(value) <= {'schema', 'imageSourceCommit', 'workloads', 'note'}, 'unknown population field')
    default = value.get('imageSourceCommit')
    require(isinstance(default, str) and SHA.fullmatch(default), 'population default commit is not a full SHA')
    workloads = value.get('workloads', {})
    require(isinstance(workloads, dict), 'malformed population workloads')
    images = set()
    for workload, entry in workloads.items():
        require(NAME.fullmatch(workload), f'malformed workload name {workload!r}')
        require(isinstance(entry, dict) and set(entry) == {'image', 'component', 'sourceCommit'},
                f'{workload}: an exception names exactly image, component and sourceCommit')
        require(SHA.fullmatch(str(entry['sourceCommit'])), f'{workload}: source commit is not a full SHA')
        require(entry['sourceCommit'] != default, f'{workload}: an exception built from the default commit is no exception')
        require(NAME.fullmatch(str(entry['image'])) and entry['image'] not in images,
                f'{workload}: image name malformed or declared twice')
        images.add(entry['image'])
    return {'schema': SCHEMA, 'imageSourceCommit': default, 'workloads': workloads}


def load(path=None):
    return parse(json.loads(Path(path or DEFAULT).read_text()))


def exceptions(population):
    """image name -> the exception that builds it."""
    return {e['image']: dict(e, workload=w) for w, e in population['workloads'].items()}


def commits(population):
    return {population['imageSourceCommit']} | {e['sourceCommit'] for e in population['workloads'].values()}


def check_images(population, built, default_images):
    """built: private image name -> the source commit its label names. default_images: the names built from the
    default commit. Refuses an image from neither declared commit, any undeclared mix, and a missing or extra image."""
    extra = exceptions(population)
    require(set(built) == set(default_images) | set(extra),
            f'image population differs from the declaration: missing {sorted((set(default_images) | set(extra)) - set(built))}, '
            f'undeclared {sorted(set(built) - set(default_images) - set(extra))}')
    for name, commit in built.items():
        require(commit in commits(population), f'{name}: built from {commit}, which is neither declared commit')
        want = extra[name]['sourceCommit'] if name in extra else population['imageSourceCommit']
        require(commit == want, f'{name}: built from {commit}, declared {want} (an undeclared mix)')


def workload_image(population, workload):
    entry = population['workloads'].get(workload)
    return entry['image'] if entry else None
