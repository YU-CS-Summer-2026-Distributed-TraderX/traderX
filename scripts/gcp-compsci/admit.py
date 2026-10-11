#!/usr/bin/env python3
"""Admit an explicit CompSci cold-demo pack from independent payload evidence.

This tier adapter reuses RI20 parsing, slot coverage, digest and target rules.
It supports a separate full-ancestry build-context/compiled-payload producer and
locked upstream OCI producer; it does not relabel these as RI18 attestations.
No cluster mutation, image repair, compatibility inference or secret access.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
TARGET = {'context':'gke_project-compsci-484414_us-east1-b_traderx-demo',
          'project':'project-compsci-484414','location':'us-east1-b',
          'cluster':'traderx-demo','namespace':'traderx'}


def module(name, path):
    spec=importlib.util.spec_from_file_location(name,path)
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


ri20=module('ri20_companion',ROOT/'scripts/yu15/image-admission.py')
derive=module('companion_derivation',ROOT/'scripts/yu15/generation-derivation.py')
require=ri20.require


def digest(raw): return hashlib.sha256(raw).hexdigest()


def payload(value):
    require(isinstance(value,dict) and value,'empty/malformed payload population')
    for path,entry in value.items():
        require(isinstance(path,str) and path and not Path(path).is_absolute()
                and '..' not in Path(path).parts,'unbounded payload path')
        require(isinstance(entry,list) and len(entry)==2,'malformed payload entry')
        require(entry[0] in ('file','symlink'),'unsupported payload entry type')
        require(isinstance(entry[1],str) and bool(entry[1]),'missing payload content')
        if entry[0]=='file':require(ri20.sha(entry[1]),'malformed payload content digest')
    return digest(json.dumps(value,sort_keys=True,separators=(',',':')).encode())


def executable_architecture(record):
    output=record.get('output','')
    return (bool(re.search(r'os\.arch = amd64\b',output)) or
            bool(re.search(r'Architecture:\s+x64\b',output)) or output.strip()=='x64' or
            record.get('actualNodeArchitecture')=='x64' or
            record.get('actualExecutable')=='ELF64 x86-64' or
            any(case.get('case')=='complete candidate' and case.get('proof',{}).get('arch')=='x64'
                and case['proof'].get('health',{}).get('ok') is True
                for case in record.get('cases',[])))


def read_evidence(folder, binding):
    require(isinstance(binding,dict) and set(binding)=={'path','sha256'},'missing evidence binding')
    path=Path(binding['path'])
    require(not path.is_absolute() and '..' not in path.parts,'unbounded evidence path')
    target=folder/path
    require(target.is_file() and not target.is_symlink() and target.resolve().is_relative_to(folder.resolve()),'missing/unbounded evidence')
    raw=target.read_bytes();require(digest(raw)==binding['sha256'],'evidence content changed')
    return raw


def validate(root, rendered, plan, artifacts, evidence, receipt, current_source_hash):
    require(plan.get('schema')=='traderx-compsci-intended-deployment-v1','unsupported profile plan')
    require(plan.get('target')==TARGET,'wrong explicit deployment target')
    require(plan.get('state')=='YU18-risk-integration','wrong selected state')
    require(plan.get('renderedSha256')==digest(rendered.encode()),'rendered configuration changed')
    require(receipt.get('schema')=='traderx-generation-derivation-v1','unsupported derivation evidence')
    require(receipt.get('state')==plan['state'] and receipt.get('role')=='fix','wrong generation state/role')
    require(set(receipt.get('ancestry',[]))==ri20.lineage(root,plan['state']),
            'derivation does not cover the complete current state ancestry')
    require(receipt.get('sourceInputsSha256')==current_source_hash,'current source differs from independent derivation')
    require(receipt.get('contexts')==plan.get('contexts') and receipt['contexts'],'missing/different intended contexts')
    require(len(receipt['contexts'])==11,'incomplete selected build-context population')
    require(plan.get('imageSourceCommit')=='6156ad222b3445cb12d58e9c4905c7dcd68c2222','unreviewed image dependency')
    require(ri20.sha(plan.get('imageSourceInputsSha256')),'missing original image build-input identity')
    actual_slots=ri20.slots(ri20.documents(rendered),TARGET['namespace'])
    require(actual_slots==plan.get('slots'),'exact image-slot coverage/reference mismatch')
    require(artifacts.get('schema')=='traderx-compsci-inspected-artifacts-v1','unsupported measured inventory')
    rows=artifacts.get('artifacts');require(isinstance(rows,list) and rows,'empty artifact inventory')
    by_ref={}
    for row in rows:
        ref=row.get('reference');require(ri20.pinned(ref) and ref not in by_ref,'mutable/duplicate artifact identity');by_ref[ref]=row
    require(set(by_ref)==set(actual_slots.values()),'inventory does not cover exact manifest image population')
    expected=plan.get('artifacts');require(isinstance(expected,dict) and set(expected)==set(by_ref),'expected artifact population differs')
    checked=[]
    for ref,want in expected.items():
        got=by_ref[ref]
        require(want.get('provider')==got.get('provider'),'artifact producer mismatch')
        require(got.get('platform')==want.get('platform')=='linux/amd64','wrong image platform')
        raw=read_evidence(evidence,got['manifest']);manifest=ri20.decode(raw)
        require(digest(raw)==ref.split('@sha256:',1)[1],'registry manifest does not match immutable reference')
        if 'manifests' in manifest:
            descriptors=[m for m in manifest['manifests'] if m.get('platform')=={'architecture':'amd64','os':'linux'}]
            require(len(descriptors)==1,'missing/ambiguous amd64 platform manifest')
            child=read_evidence(evidence,got['platformManifest'])
            require('sha256:'+digest(child)==descriptors[0]['digest']==want.get('platformManifestDigest'),
                    'platform manifest differs from pinned index')
            manifest=ri20.decode(child)
        require(isinstance(manifest.get('layers'),list) and manifest['layers'],'empty OCI layer population')
        config=ri20.decode(read_evidence(evidence,got['config']))
        require(config.get('architecture')=='amd64' and config.get('os')=='linux','OCI config platform mismatch')
        layer_ids=config.get('rootfs',{}).get('diff_ids')
        require(isinstance(layer_ids,list) and len(layer_ids)==len(manifest['layers'])
                and all(re.fullmatch(r'sha256:[0-9a-f]{64}',x) for x in layer_ids),'incomplete/malformed OCI filesystem identity')
        require(manifest.get('config',{}).get('digest')==want.get('configDigest'),'intended OCI config differs')
        require(layer_ids==want.get('uncompressedLayers'),'intended OCI filesystem differs')
        provider=want['provider']
        if provider=='compiled-context-v1':
            component=want.get('component')
            if component=='web-front-end-console':
                current=derive.provenance(root).tree_hash(root/'web-front-end-console')
                require(current==plan.get('consoleSourceSha256'),'root console source changed')
            else:
                require(component in receipt['contexts'],'component absent from full derivation')
                current=receipt['contexts'][component]
                require(derive.provenance(root).tree_hash(root/'generated/code/target-generated'/component)==current,'generated context changed after derivation')
            require(current==want.get('contextSha256')==got.get('contextSha256'),'intended/source context differs')
            labels=config.get('config',{}).get('Labels',{})
            require(labels.get('dev.traderx.source.commit')==plan['imageSourceCommit'],'image source label differs')
            require(labels.get('dev.traderx.derivation.sha256')==plan['imageSourceInputsSha256'],'image full-build-input label differs')
            if derive.provenance(root).LABEL in labels:
                require(ri20.decode(labels[derive.provenance(root).LABEL]).get('role')=='fix','STP pre payload refused')
            expected_entries=ri20.decode(read_evidence(evidence,want['payload']))
            actual_entries=ri20.decode(read_evidence(evidence,got['payload']))
            require(payload(expected_entries)==want.get('payloadSha256'),'expected compiled payload changed')
            require(payload(actual_entries)==want['payloadSha256'],'inspected packaged payload differs from independent build')
            require(expected_entries==actual_entries,'packaged entry coverage differs')
            require(got.get('imageId')==want.get('imageId'),'inspected image differs from reviewed build')
            executable=ri20.decode(read_evidence(evidence,got['executable']))
            require(executable.get('imageId')==want['imageId'] and executable_architecture(executable),
                    'missing/wrong actual executable architecture proof')
            checked.append({'reference':ref,'provider':provider,'payloadFiles':len(actual_entries)})
        elif provider=='locked-upstream-v1':
            require(ref in plan.get('upstreamReferences',[]),'unreviewed upstream image')
            checked.append({'reference':ref,'provider':provider,'layers':len(layer_ids)})
        else:require(False,'unsupported artifact producer')
    require(len(checked)==20 and len(actual_slots)==23,'unexpected CompSci image population')
    return {'schema':'traderx-compsci-image-admission-v1','verdict':'admitted-offline',
            'target':TARGET,'state':plan['state'],'renderedSha256':plan['renderedSha256'],
            'sourceInputsSha256':current_source_hash,'imageSourceCommit':plan['imageSourceCommit'],
            'slots':actual_slots,'artifacts':checked,'compatibility':'cold-demo-only',
            'runtimeCapacity':'not-qualified','applicationRolloutAuthorization':'not-inferred'}


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root',type=Path,default=ROOT)
    parser.add_argument('--rendered',type=Path,required=True)
    parser.add_argument('--expected',type=Path,required=True)
    parser.add_argument('--artifacts',type=Path,required=True)
    parser.add_argument('--derivation',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    for key in TARGET:parser.add_argument('--'+key,required=True)
    args=parser.parse_args()
    require({key:getattr(args,key) for key in TARGET}==TARGET,'operator target differs from profile')
    inputs=derive.inputs(args.root.resolve())
    blobs={name:getattr(args,name).read_bytes() for name in ['rendered','expected','artifacts','derivation']}
    report=validate(args.root.resolve(),blobs['rendered'].decode(),ri20.decode(blobs['expected']),
                    ri20.decode(blobs['artifacts']),args.artifacts.parent,ri20.decode(blobs['derivation']),derive.source_hash(inputs))
    require(derive.inputs(args.root.resolve())==inputs,'source changed during image admission')
    require(all(getattr(args,k).read_bytes()==v for k,v in blobs.items()),'admission inputs changed during verification')
    args.output.mkdir(parents=True,exist_ok=False)
    (args.output/'admitted.yaml').write_bytes(blobs['rendered'])
    report.update(expectedSha256=digest(blobs['expected']),artifactsSha256=digest(blobs['artifacts']),
                  derivationSha256=digest(blobs['derivation']))
    (args.output/'admission.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
