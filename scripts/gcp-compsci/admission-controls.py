#!/usr/bin/env python3
"""Discriminating refusal controls against a real, nonempty measured pack."""
import argparse
import copy
import importlib.util
import json
from pathlib import Path
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('controls_admit',Path(__file__).with_name('admit.py'))
admit=importlib.util.module_from_spec(spec);spec.loader.exec_module(admit)


def controls(root, folder):
    rendered=(folder/'candidate.yaml').read_text();plan=admit.ri20.read(folder/'expected.json')
    inventory=admit.ri20.read(folder/'artifacts.json');receipt=admit.ri20.read(folder/'derivation.json')
    source=admit.derive.source_hash(admit.derive.inputs(root))
    def check(p=plan,i=inventory,r=receipt,s=source):return admit.validate(root,rendered,p,i,folder,r,s)
    report=check();assert len(report['artifacts'])==20 and len(report['slots'])==23
    print('PASS nonempty measured baseline: 20 images / 23 slots')
    count=1
    def refuses(label,action):
        nonlocal count
        try:action()
        except admit.ri20.Refusal:print('PASS refusal: '+label);count+=1;return
        raise AssertionError('control unexpectedly passed: '+label)
    def plan_mutation(label,mutate):
        p=copy.deepcopy(plan);mutate(p);refuses(label,lambda:check(p=p))
    def inventory_mutation(label,mutate):
        i=copy.deepcopy(inventory);mutate(i);refuses(label,lambda:check(i=i))
    refuses('current source changed',lambda:check(s='0'*64))
    plan_mutation('wrong target',lambda p:p['target'].update(project='wrong-project'))
    plan_mutation('changed rendered bytes',lambda p:p.update(renderedSha256='0'*64))
    plan_mutation('missing intended slot',lambda p:p['slots'].pop(next(iter(p['slots']))))
    plan_mutation('mutable intended image',lambda p:p['slots'].update({next(iter(p['slots'])):'traderx/cluster-node:latest'}))
    plan_mutation('unreviewed image dependency',lambda p:p.update(imageSourceCommit='0'*40))
    plan_mutation('changed original build-input identity',lambda p:p.update(imageSourceInputsSha256='0'*64))
    inventory_mutation('empty image population',lambda i:i.update(artifacts=[]))
    inventory_mutation('duplicate inspected image',lambda i:i['artifacts'].append(copy.deepcopy(i['artifacts'][0])))
    inventory_mutation('wrong platform',lambda i:i['artifacts'][0].update(platform='linux/arm64'))
    inventory_mutation('wrong inspected image ID',lambda i:i['artifacts'][0].update(imageId='sha256:'+'0'*64))
    inventory_mutation('unbounded evidence path',lambda i:i['artifacts'][0]['payload'].update(path='../outside.json'))
    inventory_mutation('changed evidence hash',lambda i:i['artifacts'][0]['payload'].update(sha256='0'*64))
    first=inventory['artifacts'][0];old=admit.read_evidence
    def altered(folder,binding):
        raw=old(folder,binding)
        if binding==first['payload']:
            entries=json.loads(raw);key=next(k for k,v in entries.items() if v[0]=='file');entries[key]=['file','0'*64]
            return json.dumps(entries).encode()
        return raw
    with patch.object(admit,'read_evidence',side_effect=altered):
        refuses('changed packaged byte despite measured reference',lambda:check())
    def empty(folder,binding):return b'{}' if binding==first['payload'] else old(folder,binding)
    with patch.object(admit,'read_evidence',side_effect=empty):refuses('empty packaged payload',lambda:check())
    def wrong_executable(folder,binding):
        raw=old(folder,binding)
        if binding==first['executable']:
            record=json.loads(raw);record['output']='os.arch = aarch64';return json.dumps(record).encode()
        return raw
    with patch.object(admit,'read_evidence',side_effect=wrong_executable):
        refuses('mislabeled actual executable architecture',lambda:check())
    require_upstream=next(ref for ref,x in plan['artifacts'].items() if x['provider']=='locked-upstream-v1')
    plan_mutation('unreviewed upstream',lambda p:p['upstreamReferences'].remove(require_upstream))
    print('PASS '+str(count)+' admission controls')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True);p.add_argument('--evidence',type=Path,required=True)
    a=p.parse_args();controls(a.root,a.evidence)
