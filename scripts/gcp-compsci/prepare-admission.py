#!/usr/bin/env python3
"""Package independent CompSci measurements into exact RI20 tier-adapter inputs.

The measurement directory must contain source-derived reference compilations,
separately inspected image payloads and registry OCI identities. This command
never manufactures missing measurements or fetches/builds/applies anything.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('companion_admit',Path(__file__).with_name('admit.py'))
admit=importlib.util.module_from_spec(spec);spec.loader.exec_module(admit)


def package(measured, published_path, upstream_path, build_dir, derivation_path, rendered_path, output):
    output.mkdir(parents=True,exist_ok=False)
    evidence=output/'evidence';evidence.mkdir()
    def read(path):return admit.ri20.decode(path.read_bytes())
    def write(name,value):
        path=evidence/name;path.write_text(json.dumps(value,indent=2)+'\n')
        return {'path':'evidence/'+name,'sha256':admit.digest(path.read_bytes())}
    def copy(name,path):
        target=evidence/name;shutil.copy2(path,target)
        return {'path':'evidence/'+name,'sha256':admit.digest(target.read_bytes())}
    published=read(published_path);original=read(build_dir/'images.json');original_receipt=read(build_dir/'derivation.json')
    fresh=read(derivation_path);console=read(build_dir/'console-source.json');upstream=read(upstream_path)
    architecture={x['image']:x for file in ['runtime-architecture.json','remaining-runtime-architecture.json']
                  for x in read(build_dir.parent/file)}
    architecture['web-front-end-console']=read(build_dir.parent/'console-native-proof.json')
    admit.require(published['sourceCommit']==original['sourceCommit']==admit.REVIEWED_IMAGE_COMMIT,'wrong image dependency')
    admit.require(fresh['contexts']==original_receipt['contexts'],'current full-ancestry contexts differ from original image inputs')
    admit.require(len(published['images'])==len(original['images'])==admit.PRIVATE_IMAGES and len(upstream)==admit.UPSTREAM_IMAGES,'incomplete input image population')
    plan={'schema':'traderx-compsci-intended-deployment-v1','target':admit.TARGET,'state':'YU18-risk-integration',
          'imageSourceCommit':published['sourceCommit'],'imageSourceInputsSha256':original_receipt['sourceInputsSha256'],
          'buildSources':{},
          'contexts':fresh['contexts'],'consoleSourceSha256':console['sha256'],
          'renderedSha256':admit.digest(rendered_path.read_bytes()),
          'slots':admit.ri20.slots(admit.ri20.documents(rendered_path.read_text()),'traderx'),
          'upstreamReferences':[x['reference'] for x in upstream.values()],'artifacts':{}}
    inventory={'schema':'traderx-compsci-inspected-artifacts-v1','artifacts':[]}
    for name,item in published['images'].items():
        old=original['images'][name];comparison=read(measured/(name+'-payload-comparison.json'))
        admit.require(comparison['passed'] and comparison['expectedFiles']>0 and comparison['actualFiles']>0,'failed/empty independent payload comparison')
        admit.require(comparison['expectedEntries']==comparison['actualEntries'],'expected/inspected payload differs')
        admit.require(old['imageId']==item['imageId'] and old['contextSha256']==item['sourceContextSha256'],'published/build mapping differs')
        component=old['component'];context=console['sha256'] if component=='web-front-end-console' else fresh['contexts'][component]
        admit.require(context==old['contextSha256'],'source context does not match intended image')
        build_commit=item.get('sourceCommit',published['sourceCommit'])
        build_inputs=item.get('sourceInputsSha256',original_receipt['sourceInputsSha256'])
        admit.require(build_commit==old.get('sourceCommit',original['sourceCommit']) and
                      build_inputs==old.get('sourceInputsSha256',original_receipt['sourceInputsSha256']),
                      'published/original per-image build identity differs')
        admit.require(build_commit not in plan['buildSources'] or plan['buildSources'][build_commit]==build_inputs,
                      'conflicting build-input identity for source commit')
        plan['buildSources'][build_commit]=build_inputs
        manifest=read(measured/'private-oci'/(name+'-manifest.json'))
        child=measured/'private-oci'/(name+'-platform-manifest.json')
        selected=read(child) if child.exists() else manifest
        config=read(measured/'private-oci'/(name+'-config.json'))
        want={'provider':'compiled-context-v1','platform':'linux/amd64','component':component,
              'sourceCommit':build_commit,'sourceInputsSha256':build_inputs,
              'contextSha256':context,'imageId':item['imageId'],'configDigest':selected['config']['digest'],
              'uncompressedLayers':config['rootfs']['diff_ids'],
              'payloadSha256':admit.payload(comparison['expectedEntries']),
              'payload':write(name+'-expected-payload.json',comparison['expectedEntries'])}
        got={'reference':item['reference'],'provider':want['provider'],'platform':'linux/amd64',
             'imageId':item['imageId'],'contextSha256':context,
             'payload':write(name+'-inspected-payload.json',comparison['actualEntries']),
             'executable':write(name+'-executable.json',architecture[name]),
             'manifest':copy(name+'-manifest.json',measured/'private-oci'/(name+'-manifest.json')),
             'config':copy(name+'-config.json',measured/'private-oci'/(name+'-config.json'))}
        if child.exists():
            want['platformManifestDigest']='sha256:'+admit.digest(child.read_bytes())
            got['platformManifest']=copy(name+'-platform-manifest.json',child)
        plan['artifacts'][item['reference']]=want;inventory['artifacts'].append(got)
    for tag,item in upstream.items():
        name=tag.split(':')[0].replace('/','-');manifest_path=measured/'upstream'/(name+'-manifest.json');config_path=measured/'upstream'/(name+'-config.json')
        manifest=read(manifest_path);config=read(config_path)
        plan['artifacts'][item['reference']]={'provider':'locked-upstream-v1','platform':'linux/amd64',
            'configDigest':manifest['config']['digest'],'uncompressedLayers':config['rootfs']['diff_ids']}
        inventory['artifacts'].append({'reference':item['reference'],'provider':'locked-upstream-v1','platform':'linux/amd64',
            'manifest':copy(name+'-manifest.json',manifest_path),'config':copy(name+'-config.json',config_path)})
    (output/'expected.json').write_text(json.dumps(plan,indent=2)+'\n')
    (output/'artifacts.json').write_text(json.dumps(inventory,indent=2)+'\n')
    shutil.copy2(derivation_path,output/'derivation.json');shutil.copy2(rendered_path,output/'candidate.yaml')
    print(f'Packaged {admit.PRIVATE_IMAGES} independently compared application payloads + {admit.UPSTREAM_IMAGES} upstream images; admission still required.')


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['measurements','published','upstream-lock','build-records','derivation','rendered','output']:
        p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();package(a.measurements,a.published,a.upstream_lock,a.build_records,a.derivation,a.rendered,a.output)
