#!/usr/bin/env python3
"""RI20 read-only image admission. Offline evidence is a trusted review input,
not registry authentication, node availability, operator permission or compatibility.
"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys


class Refusal(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise Refusal(message)


def unique_object(pairs):
    obj = {}
    for key, value in pairs:
        require(key not in obj, f'duplicate JSON key: {key}')
        obj[key] = value
    return obj


def decode(data):
    return json.loads(data, object_pairs_hook=unique_object)


def read(path):
    return decode(Path(path).read_text())


def command(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=90).stdout


def stp_helper(root):
    spec = importlib.util.spec_from_file_location('ri18_contract', root / 'scripts/yu15/stp-image-provenance.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def sha(value):
    return isinstance(value, str) and re.fullmatch(r'[0-9a-f]{64}', value) is not None


def pinned(ref):
    return isinstance(ref, str) and re.fullmatch(r'[^\s@]+@sha256:[0-9a-f]{64}', ref) is not None


def normal_ref(ref):
    require(isinstance(ref, str) and ref and not re.search(r'\s', ref), 'malformed image reference')
    require(ref != '<none>' and '<none>' not in ref, 'tagless content is not an image reference')
    name, separator, digest = ref.partition('@')
    if separator:
        require(re.fullmatch(r'sha256:[0-9a-f]{64}', digest), 'malformed repository digest')
        # Docker permits name:tag@digest, but digest identity is independent of the tag.
        if ':' in name.rsplit('/', 1)[-1]:
            name = name.rsplit(':', 1)[0]
    else:
        require(':' in name.rsplit('/', 1)[-1], 'explicit tag or repository digest required')
    first = name.split('/')[0]
    if '/' not in name or ('.' not in first and ':' not in first and first != 'localhost'):
        name = 'docker.io/' + name
    if name.startswith('index.docker.io/'):
        name = 'docker.io/' + name[len('index.docker.io/'):]
    if name.startswith('docker.io/') and '/' not in name[len('docker.io/'):]:
        name = 'docker.io/library/' + name[len('docker.io/'):]
    return name + ('@' + digest if separator else '')


def node_refs(args):
    """Require a resolvable repository reference on every node; never load/retag.
    This runner adapter is intentionally kind-only. Cloud inventory needs a separate
    authorized adapter, rather than silently succeeding for a non-kind context.
    """
    require(args.context.startswith('kind-'), 'node reference adapter supports explicit kind contexts only; supply an authorized tier adapter for other targets')
    selected = normal_ref(args.image)
    nodes = decode(command(['kubectl', '--context', args.context, 'get', 'nodes', '-o', 'json']))
    require(isinstance(nodes, dict) and isinstance(nodes.get('items'), list) and nodes['items'], 'node inventory is missing/empty')
    names = []
    for node in nodes['items']:
        require(isinstance(node, dict), 'malformed node inventory')
        name = node.get('metadata', {}).get('name')
        require(isinstance(name, str) and re.fullmatch(r'[a-z0-9][a-z0-9.-]*', name), 'invalid node name')
        require(name not in names, 'duplicate node identity')
        names.append(name)
    missing = []
    for name in names:
        inventory = decode(command(['docker', 'exec', name, 'crictl', 'images', '-o', 'json']))
        require(isinstance(inventory, dict) and isinstance(inventory.get('images'), list), f'{name}: malformed CRI inventory')
        matches = set()
        for record in inventory['images']:
            require(isinstance(record, dict) and isinstance(record.get('id'), str) and re.fullmatch(r'sha256:[0-9a-f]{64}', record['id']), f'{name}: malformed CRI image record')
            refs = []
            for field in ('repoTags', 'repoDigests'):
                values = record.get(field, [])
                require(isinstance(values, list) and all(isinstance(v, str) for v in values), f'{name}: malformed {field}')
                refs.extend(values)
            for ref in refs:
                # <none> is a content-only record, never evidence of ref resolution.
                if '<none>' in ref:
                    continue
                if normal_ref(ref) == selected:
                    matches.add(record['id'])
        require(len(matches) <= 1, f'{name}: ambiguous image reference {args.image}')
        if not matches:
            missing.append(name)
    require(not missing, f'{args.image} does not resolve on nodes: {", ".join(missing)}; explicitly load the intended reference before running proofs (no automatic repair)')
    print(f'[ok] selected reference {args.image} resolves on all {len(names)} nodes; content identity/compatibility not inferred')


def documents(data):
    try:
        value = decode(data)
    except json.JSONDecodeError:
        try:
            import yaml
        except ImportError as error:
            raise Refusal('rendered YAML requires an installed PyYAML parser; set IMAGE_ADMISSION_PYTHON to that interpreter, or validate structured JSON with admit') from error
        # Refuse duplicate YAML keys, just as for JSON. No unsafe constructors.
        class Loader(yaml.SafeLoader):
            pass
        def mapping(loader, node):
            loader.flatten_mapping(node)
            return unique_object(loader.construct_pairs(node))
        Loader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
        try:
            value = list(yaml.load_all(data, Loader=Loader))
        except yaml.YAMLError as error:
            raise Refusal(f'malformed rendered YAML: {error}') from error
    if isinstance(value, dict):
        value = [value]
    require(isinstance(value, list) and value, 'rendered document population is empty')
    require(all(isinstance(x, dict) for x in value), 'malformed/empty rendered document')
    flattened = []
    def expand(obj):
        if obj.get('kind') == 'List':
            require(isinstance(obj.get('items'), list) and obj['items'] and all(isinstance(x, dict) for x in obj['items']), 'malformed/empty Kubernetes List')
            for child in obj['items']:
                expand(child)
        else:
            flattened.append(obj)
    for obj in value:
        expand(obj)
    return flattened


def slots(objects, namespace):
    result = {}
    identities = set()
    workloads = {'Deployment', 'StatefulSet', 'DaemonSet', 'ReplicaSet', 'ReplicationController', 'Job'}
    for obj in objects:
        kind, meta = obj.get('kind'), obj.get('metadata')
        require(isinstance(kind, str) and isinstance(meta, dict) and isinstance(meta.get('name'), str), 'rendered object lacks kind/name')
        ns = meta.get('namespace', namespace)
        key = (kind, ns, meta['name'])
        require(key not in identities, f'duplicate rendered object: {key}')
        identities.add(key)
        if kind in workloads:
            pod = obj.get('spec', {}).get('template', {}).get('spec')
        elif kind == 'CronJob':
            pod = obj.get('spec', {}).get('jobTemplate', {}).get('spec', {}).get('template', {}).get('spec')
        elif kind == 'Pod':
            pod = obj.get('spec')
        else:
            # Unknown resource types with pod/image fields are refused, never omitted.
            def image_fields(value):
                if isinstance(value, dict):
                    return any(k in ('containers', 'initContainers', 'ephemeralContainers', 'image') or image_fields(v) for k, v in value.items())
                return isinstance(value, list) and any(image_fields(v) for v in value)
            require(not image_fields(obj.get('spec', {})), f'unsupported rendered image-bearing kind {kind}')
            continue
        require(ns == namespace, f'{kind}/{meta["name"]}: namespace differs from explicit target')
        require(isinstance(pod, dict) and isinstance(pod.get('containers'), list) and pod['containers'], f'{kind}/{meta["name"]}: missing containers')
        for group in ('containers', 'initContainers', 'ephemeralContainers'):
            containers = pod.get(group, [])
            require(isinstance(containers, list), 'malformed container group')
            for container in containers:
                require(isinstance(container, dict) and isinstance(container.get('name'), str) and isinstance(container.get('image'), str), 'container lacks name/image')
                key = '/'.join((ns, kind, meta['name'], group, container['name']))
                require(key not in result, f'duplicate rendered container: {key}')
                result[key] = container['image']
    require(result, 'no rendered image-bearing containers')
    return result


def bounded(root, relative):
    require(isinstance(relative, str) and relative and not Path(relative).is_absolute() and '..' not in Path(relative).parts, 'source/generation path must be repository relative')
    path = root / relative
    require(path.exists() and path.resolve().is_relative_to(root.resolve()), f'missing/unbounded source or generated path: {relative}')
    return path


def lineage(root, state):
    catalog = read(root / 'catalog/state-catalog.json')
    states = {s['id']: s for s in catalog['states']}
    require(state in states, f'unknown explicit state {state}')
    found = set()
    def visit(value):
        require(value in states, f'unknown parent state {value}')
        if value in found:
            return
        found.add(value)
        for parent in states[value].get('previous', []):
            visit(parent)
    visit(state)
    return found


def state_rank(state):
    match = re.fullmatch(r'(YU)?(\d+)-.+', state)
    require(match is not None, f'unsupported state ordering: {state}')
    return (bool(match[1]), int(match[2]))


def local_render_inputs(root, folder):
    """Kustomize can fetch remote resources even without a cluster. Bound its
    structured input closure before calling it; plugins/Helm are unsupported.
    """
    root = root.resolve()
    folder = folder.resolve()
    paths = set()
    active = set()
    def local(base, name):
        require(isinstance(name, str) and name and not Path(name).is_absolute() and '://' not in name and not name.startswith('git@') and '?' not in name,
                'remote/absolute kustomize inputs are not offline admission inputs')
        path = base / name
        require(path.exists() and path.resolve().is_relative_to(root), f'unbounded/missing local kustomize input: {name}')
        require(not path.is_symlink(), 'symlink kustomize input refused')
        if path.is_dir():
            visit(path)
        else:
            paths.add(path)
    def visit(base):
        require(base not in active, 'cyclic local kustomize input')
        choices = [base / name for name in ('kustomization.yaml','kustomization.yml','Kustomization') if (base / name).is_file()]
        require(len(choices) == 1, 'missing/ambiguous local kustomization')
        path = choices[0]
        if path in paths:
            return
        active.add(base)
        obj = documents(path.read_text())
        require(len(obj) == 1, 'malformed kustomization')
        obj = obj[0]
        require(not any(obj.get(k) for k in ('helmCharts','helmGlobals','generators','transformers')), 'offline admission does not support Helm or external kustomize generators/transformers')
        for field in ('resources','bases','components','configurations'):
            values = obj.get(field, [])
            require(isinstance(values, list), f'malformed kustomize {field}')
            for name in values:
                local(base, name)
        for field in ('patches','patchesJson6902','patchesStrategicMerge','replacements'):
            values = obj.get(field, [])
            require(isinstance(values, list), f'malformed kustomize {field}')
            for value in values:
                if isinstance(value, dict):
                    if 'path' in value:
                        local(base, value['path'])
                elif isinstance(value, str) and '\n' not in value:
                    local(base, value)
                else:
                    require(isinstance(value, str), 'malformed inline kustomize patch')
        for field in ('configMapGenerator','secretGenerator'):
            values = obj.get(field, [])
            require(isinstance(values, list), f'malformed kustomize {field}')
            for value in values:
                require(isinstance(value, dict), 'malformed local kustomize generator')
                for name in value.get('files', []):
                    local(base, name.split('=',1)[-1])
                for name in value.get('envs', []):
                    local(base, name)
                if value.get('env'):
                    local(base, value['env'])
        if isinstance(obj.get('openapi'), dict) and obj['openapi'].get('path'):
            local(base, obj['openapi']['path'])
        paths.add(path)
        active.remove(base)
    visit(folder)
    ri18 = stp_helper(root)
    return ri18.digest(ri18.canonical(sorted([[str(p.relative_to(root)),ri18.digest(p.read_bytes())] for p in paths])))


IDENTITY = {'component', 'buildState', 'ownerState', 'role', 'provider', 'platform', 'sourcePath', 'generatedPath',
            'ownerSha256', 'sourceSha256', 'payloadSha256', 'provenanceSha256'}


def artifact(root, expected, actual, ri18, ancestors, evidence_dir):
    require(isinstance(expected, dict) and isinstance(actual, dict), 'malformed expected artifact/inventory entry')
    for key in IDENTITY:
        require(key in expected and expected[key] == actual.get(key), f'intended artifact {key} mismatch/missing for {expected.get("reference")}')
    require(expected['role'] == 'production', 'intended artifact role must be production; STP pre images are never production')
    require(expected['ownerState'] in ancestors, 'component owner is outside selected state lineage')
    require(expected['buildState'] in ancestors, 'intended artifact build state is outside selected deployment lineage')
    build_ancestors = lineage(root, expected['buildState'])
    require(expected['ownerState'] in build_ancestors, 'component owner is outside explicit artifact build lineage')
    require(expected['provider'] in ('reviewed-artifact-v1', 'ri18-fix-v1'), 'unknown provenance producer; obtain a supported per-component review')
    require(isinstance(expected['component'], str) and re.fullmatch(r'[a-z0-9][a-z0-9-]*', expected['component']), 'invalid expected component')
    for key in ('ownerSha256', 'sourceSha256', 'payloadSha256', 'provenanceSha256'):
        require(sha(expected[key]), f'malformed {key}')
    prefix = 'specs/' + expected['ownerState'] + '/generation/'
    require(expected['sourcePath'].startswith(prefix), 'explicit source path does not belong to component owner')
    require(expected['sourcePath'] == prefix + 'runtime-overrides/' + expected['component'], 'explicit source component root required')
    require(expected['generatedPath'].startswith('generated/') and expected['generatedPath'].endswith('/code/target-generated/' + expected['component']), 'explicit generated component root required')
    # Whole generated trees may combine several ancestors. The named owner is the
    # last runtime-override layer for the explicitly intended build state, which
    # can itself be an ancestor deliberately selected by a newer deployment. All earlier transformation
    # inputs remain in the generation fingerprint or separately reviewed inputs.
    owners = [state for state in build_ancestors if (root / 'specs' / state / 'generation/runtime-overrides' / expected['component']).is_dir()]
    require(owners and max(owners, key=state_rank) == expected['ownerState'], 'declared component owner is shadowed by a later runtime layer')
    require(ri18.tree_hash(bounded(root, expected['sourcePath'])) == expected['ownerSha256'], 'owner source content changed')
    require(ri18.tree_hash(bounded(root, expected['generatedPath'])) == expected['sourceSha256'], 'generated source content changed')
    inspection = actual.get('inspection')
    require(isinstance(inspection, dict) and isinstance(inspection.get('RepoDigests'), list), 'missing/malformed immutable inspection')
    require(sha(str(inspection.get('Id', '')).removeprefix('sha256:')) and str(inspection.get('Id', '')).startswith('sha256:'), 'malformed inspected image ID')
    require(expected['platform'] in ('linux/amd64', 'linux/arm64') and expected['platform'] == inspection.get('Os', '') + '/' + inspection.get('Architecture', ''), 'inspected platform differs from explicit intended artifact')
    ref = expected['reference']
    require(actual.get('reference') == ref and pinned(ref), 'mutable/ambiguous intended image; use an exact repository @sha256 digest')
    require(any(pinned(x) and normal_ref(x) == normal_ref(ref) for x in inspection['RepoDigests']), 'rendered immutable reference absent from inspected repository digests')
    labels = inspection.get('Config', {}).get('Labels')
    require(isinstance(labels, dict), 'missing/malformed provenance labels')
    # Even a reviewed external declaration cannot launder an STP pre payload.
    if ri18.LABEL in labels:
        stp = decode(labels[ri18.LABEL])
        require(isinstance(stp, dict) and stp.get('schema') == ri18.SCHEMA and stp.get('role') == 'fix', 'wrong STP image role; pre/STP-disabled payload refused')
    label = ri18.LABEL if expected['provider'] == 'ri18-fix-v1' else 'dev.traderx.image.provenance'
    require(isinstance(labels.get(label), str), f'missing provenance label {label}; unknown service builders are not attested by RI18')
    provenance = decode(labels[label])
    require(isinstance(provenance, dict), 'malformed provenance')
    require(ri18.digest(ri18.canonical(provenance)) == expected['provenanceSha256'], 'provenance differs from independent expected artifact')
    payload = actual.get('payloadEntries')
    require(isinstance(payload, list) and payload, 'missing inspected packaged payload entries')
    require(all(isinstance(p, list) and len(p) == 2 and isinstance(p[0], str) and p[0] and not p[0].startswith('/') and '..' not in Path(p[0]).parts and sha(p[1]) for p in payload), 'malformed packaged payload entries')
    require(len({p[0] for p in payload}) == len(payload), 'duplicate packaged payload entry')
    require(ri18.digest(ri18.canonical(sorted(payload))) == expected['payloadSha256'], 'inspected packaged payload differs from expected artifact')
    if expected['provider'] == 'ri18-fix-v1':
        keys = {'schema', 'role', 'sourceSha256', 'compositionSha256', 'recipeSha256',
                'patchSha256', 'roleSha256', 'sourceRevision', 'toolchainSha256',
                'bases', 'jarSha256', 'payloadSha256', 'platform', 'patchPath'}
        require(set(provenance) == keys, 'malformed RI18 provenance shape')
        require(all(sha(v) for k,v in provenance.items() if k.endswith('Sha256')), 'malformed RI18 content digest')
        require(isinstance(provenance['sourceRevision'], str) and re.fullmatch(r'[0-9a-f]{40,64}', provenance['sourceRevision']), 'malformed RI18 source revision')
        require(provenance['platform'] in ('native', expected['platform']), 'RI18 platform differs from intended artifact')
        require(isinstance(provenance['bases'], list) and all(isinstance(b, dict) and set(b) == {'reference','imageId','pinned'} and isinstance(b['reference'], str) and re.fullmatch(r'sha256:[0-9a-f]{64}', str(b['imageId'])) and pinned(b['pinned']) for b in provenance['bases']), 'malformed RI18 base inputs')
        require(expected['component'] == 'order-matcher' and expected['generatedPath'] == 'generated/code/target-generated/order-matcher', 'RI18 attests only generated order-matcher, not every service')
        current = ri18.selected(root)
        require(all(provenance.get(k) == v for k, v in current.items()), 'RI18 selected source/composition/recipe/patch changed')
        require(provenance.get('roleSha256') == current['sourceSha256'], 'RI18 fix role source differs')
        require(provenance.get('payloadSha256') == expected['payloadSha256'], 'RI18 packaged payload differs')
        require(any(p[0].startswith('classes/') for p in payload) and any(p[0].startswith('lib/') for p in payload), 'RI18 payload requires classes/resources and dependencies')
    else:
        require(provenance.get('schema') == 'traderx-reviewed-image-v1', 'malformed/unsupported reviewed provenance schema')
        for key in IDENTITY - {'provider', 'provenanceSha256'}:
            require(provenance.get(key) == expected[key], f'reviewed provenance {key} mismatch')
        inputs = provenance.get('generationInputs')
        require(isinstance(inputs, list) and inputs, 'reviewed producer must declare bounded generation inputs')
        require(all(isinstance(i, dict) and isinstance(i.get('path'), str) and sha(i.get('sha256')) for i in inputs), 'malformed generation inputs')
        require(len({i['path'] for i in inputs}) == len(inputs), 'duplicate generation input')
        for item in inputs:
            path = bounded(root, item['path'])
            value = ri18.tree_hash(path) if path.is_dir() else ri18.digest(path.read_bytes())
            require(value == item['sha256'], f'generation input changed: {item["path"]}')
    # RI17-style consumer of separately reviewed evidence. This does not create it,
    # authenticate the reviewer, or expand the report into retained compatibility.
    review = actual.get('review')
    require(isinstance(review, dict) and isinstance(review.get('path'), str) and sha(review.get('sha256')), 'missing reviewed content evidence')
    path = evidence_dir / review['path']
    require(not Path(review['path']).is_absolute() and '..' not in Path(review['path']).parts and path.resolve().is_relative_to(evidence_dir.resolve()), 'unbounded review evidence path')
    raw = path.read_bytes()
    require(ri18.digest(raw) == review['sha256'], 'review evidence hash mismatch')
    report = decode(raw)
    require(isinstance(report, dict) and report.get('schema') == 'traderx-intended-image-review-v1' and report.get('verdict') == 'verified' and isinstance(report.get('reviewedBy'), str) and report['reviewedBy'].strip(), 'missing/unsupported intended content review')
    binding = dict(reference=ref, imageId=inspection['Id'], **{key: expected[key] for key in IDENTITY})
    require(report.get('artifact') == binding, 'review does not bind intended immutable artifact and component source')
    require(type(report.get('assertions')) is int and report['assertions'] > 0, 'review contains no assertions')
    checks = report.get('checks')
    require(isinstance(checks, dict) and all(checks.get(k) is True for k in ('immutableReference', 'packagedPayload', 'sourceMapping')), 'review must verify immutable reference, payload and source mapping; running pods are insufficient')


def admit(args, rendered):
    root = args.root.resolve()
    expected_bytes = args.expected.read_bytes()
    artifact_bytes = args.artifacts.read_bytes()
    plan = decode(expected_bytes)
    require(isinstance(plan, dict) and plan.get('schema') == 'traderx-intended-deployment-v1', 'missing/unsupported expected artifact plan')
    target = {k: getattr(args, k) for k in ('context', 'project', 'location', 'cluster', 'namespace')}
    require(all(isinstance(v, str) and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*', v) for v in target.values()), 'explicit target identity required')
    require(target['context'] == 'gke_' + '_'.join(target[k] for k in ('project', 'location', 'cluster')), 'explicit context does not match project/location/cluster')
    require(plan.get('target') == target, 'expected artifact target differs from explicit operator target')
    require(plan.get('state') == args.state and plan.get('manifestPack') == args.manifest_pack, 'expected state/manifest owner mismatch')
    ancestors = lineage(root, args.state)
    require(args.manifest_pack in ancestors, 'manifest pack is outside explicit state lineage')
    actual_slots = slots(documents(rendered), args.namespace)
    expected_slots = plan.get('slots')
    require(isinstance(expected_slots, dict) and expected_slots, 'expected component artifacts are missing/empty')
    require(set(actual_slots) == set(expected_slots), f'rendered/expected container coverage differs: unexpected={sorted(set(actual_slots)-set(expected_slots))}, absent={sorted(set(expected_slots)-set(actual_slots))}')
    inventory = decode(artifact_bytes)
    require(isinstance(inventory, dict) and inventory.get('schema') == 'traderx-offline-image-inventory-v1' and isinstance(inventory.get('artifacts'), list), 'missing/malformed offline artifact inventory')
    by_ref = {}
    for item in inventory['artifacts']:
        require(isinstance(item, dict) and isinstance(item.get('reference'), str) and item['reference'] not in by_ref, 'ambiguous/malformed artifact inventory')
        by_ref[item['reference']] = item
    ri18 = stp_helper(root)
    checked = {}
    for key, image in actual_slots.items():
        expected = expected_slots[key]
        require(isinstance(expected, dict) and pinned(image) and expected.get('reference') == image, f'{key}: rendered image is mutable or differs from intended immutable artifact: {image}')
        require(image in by_ref, f'{key}: no inspected artifact for {image}; obtain intended component provenance')
        if image in checked:
            require(checked[image] == expected, 'one immutable image has conflicting intended component ownership')
        else:
            artifact(root, expected, by_ref[image], ri18, ancestors, args.artifacts.parent)
            checked[image] = expected
    require(args.expected.read_bytes() == expected_bytes and args.artifacts.read_bytes() == artifact_bytes, 'expected/artifact evidence changed during admission')
    return dict(schema='traderx-image-admission-v1', target=target, state=args.state, manifestPack=args.manifest_pack,
                renderedSha256=ri18.digest(rendered.encode()), slots=actual_slots,
                expectedSha256=ri18.digest(expected_bytes), artifactsSha256=ri18.digest(artifact_bytes),
                verdict='admitted-offline', compatibility='not-assessed', nodeAvailability='not-assessed', operatorAuthorization='not-inferred')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='command', required=True)
    nodes = sub.add_parser('node-refs')
    nodes.add_argument('--context', required=True)
    nodes.add_argument('--image', required=True)
    for name in ('render', 'admit'):
        p = sub.add_parser(name)
        p.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[2])
        for key in ('context', 'project', 'location', 'cluster', 'namespace', 'state', 'manifest-pack'):
            p.add_argument('--' + key, required=True)
        p.add_argument('--expected', type=Path, required=True)
        p.add_argument('--artifacts', type=Path, required=True)
        if name == 'admit':
            p.add_argument('--rendered', type=Path, required=True)
        else:
            p.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'node-refs':
        node_refs(args)
        return
    if args.command == 'admit':
        report = admit(args, args.rendered.read_text())
        print(json.dumps(report, sort_keys=True))
        return
    # Only offline local kustomize: no config read, discovery, server query or apply.
    ancestors = lineage(args.root, args.state)
    require(args.manifest_pack in ancestors, 'explicit manifest pack not in state lineage')
    folder = bounded(args.root, f'specs/{args.manifest_pack}/generation/kubernetes/cluster/gke')
    render_inputs = local_render_inputs(args.root, folder)
    rendered = command(['kubectl', 'kustomize', str(folder)])
    require(local_render_inputs(args.root, folder) == render_inputs, 'local kustomize inputs changed while rendering')
    report = admit(args, rendered)
    # Explicit state ancestry, rather than highest folder in checkout, selects schema.
    relative = 'generation/runtime-overrides/kubernetes-runtime/manifests/base/database-init-configmap.yaml'
    candidates = [s for s in ancestors if (args.root / 'specs' / s / relative).is_file()]
    require(candidates, 'no database schema in selected state lineage')
    owner = max(candidates, key=state_rank)
    schema = (args.root / 'specs' / owner / relative).read_text()
    schema_docs = documents(schema)
    require(len(schema_docs) == 1 and schema_docs[0].get('kind') == 'ConfigMap' and schema_docs[0].get('metadata', {}).get('name') == 'database-init-sql', 'schema artifact is not database-init-sql ConfigMap')
    require(schema_docs[0]['metadata'].get('namespace', args.namespace) == args.namespace, 'schema namespace differs from target')
    report.update(schemaOwner=owner, schemaSha256=stp_helper(args.root).digest(schema.encode()), renderInputsSha256=render_inputs)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    for name, data in (('rendered.yaml', rendered), ('database-init.yaml', schema), ('admission.json', json.dumps(report, sort_keys=True, indent=2) + '\n')):
        (args.output_dir / name).write_text(data)
    print(f'[ok] offline admission: {len(report["slots"])} container slots; snapshots in {args.output_dir}; no deployment/compatibility inferred')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, AttributeError, subprocess.SubprocessError) as error:
        print(f'[FAIL] image admission: {error}', file=sys.stderr)
        sys.exit(1)
