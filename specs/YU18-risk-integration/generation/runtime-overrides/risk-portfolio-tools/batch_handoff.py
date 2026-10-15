"""Prepare a bounded job offline; explicit submit performs one user-authorized submission and UID upload."""
import argparse
import copy
import json
import os
import subprocess
from pathlib import Path

from batch_prepare import ACCOUNT, PROJECT, REGION, BUCKET, read, write
from batch_artifacts import require, verify_download
from cloud_handoff import checked, admit_build
from corpus import digest, encoded
from batch_bootstrap import validate_identity


def gcloud(*args):
    return subprocess.check_output(['gcloud', '--account='+ACCOUNT, '--project='+PROJECT, *args])


def admission(work):
    expected, package, manifest = checked(work)
    registry = read(work / 'registry-image.json')['image_summary']
    receipt = admit_build(work, read(work / 'build-result.json'), (work/'build-id.txt').read_text().strip(), registry['digest'])
    require(registry['fully_qualified_digest'] == receipt['image_uri'] and read(work/'image-receipt.json') == receipt,
            'registry/retained image receipt mismatch')
    return package, manifest, receipt


def configuration(contract, source):
    variables = {'PILOT_RUN_ID': contract['run_id'], 'PILOT_BUCKET': BUCKET,
                 'PILOT_IMAGE': contract['image'], 'PILOT_INPUT_GENERATION': contract['input']['generation'],
                 'PILOT_HANDOFF_CONTRACT': encoded(contract).decode()}
    return {'taskGroups': [{'taskCount': '1', 'parallelism': '1', 'taskCountPerNode': '1',
        'taskSpec': {'runnables': [{'container': {'imageUri': contract['image'], 'entrypoint': 'python',
             'commands': ['-c', source], 'options': '--cpus=2 --memory=6g --pids-limit=256'}}],
          'computeResource': {'cpuMilli': '2000', 'memoryMib': '6144'}, 'maxRetryCount': 0,
          'maxRunDuration': '1200s', 'environment': {'variables': variables}}}],
        'allocationPolicy': {'instances': [{'policy': {'machineType': 'e2-standard-2',
            'provisioningModel': 'STANDARD', 'bootDisk': {'type': 'pd-balanced', 'sizeGb': '30'}}}],
            'location': {'allowedLocations': ['regions/'+REGION]},
            'serviceAccount': {'email': 'risk-batch-worker@'+PROJECT+'.iam.gserviceaccount.com',
                              'scopes': ['https://www.googleapis.com/auth/cloud-platform']},
            'network': {'networkInterfaces': [{'network': 'projects/'+PROJECT+'/global/networks/default'}]},
            'labels': {'workload': 'risk-cpu-pilot'}},
        'logsPolicy': {'destination': 'CLOUD_LOGGING'}, 'labels': {'workload': 'risk-cpu-pilot'}}


def prepare(work, metadata, downloaded):
    package, manifest, image = admission(work)
    meta = read(metadata)
    name = 'inputs/'+manifest['run_id']+'/request.json'
    require((meta.get('bucket') == BUCKET and meta.get('name') == name) or
            meta.get('storage_url') == 'gs://'+BUCKET+'/'+name, 'wrong input object')
    generation = str(meta.get('generation',''))
    require(generation.isdigit() and int(generation)>0 and
            int(meta['size']) == (package/'request.json').stat().st_size and
            digest(downloaded) == manifest['input_sha256'], 'input generation/bytes mismatch')
    source = Path(__file__).with_name('batch_bootstrap.py').read_text()
    contract = {'version': 2, 'project_id': PROJECT, 'project_number': '918355398369', 'region': REGION,
        'run_id': manifest['run_id'], 'image': image['image_uri'], 'build_sha256': image['build_sha256'],
        'package_sha256': image['package_sha256'], 'bootstrap_sha256': digest(Path(__file__).with_name('batch_bootstrap.py')),
        'engine_revision': manifest['engine_revision'], 'numerical_revision': manifest['numerical_revision'],
        'input': {'bucket': BUCKET, 'object': name, 'generation': generation, 'sha256': manifest['input_sha256']}}
    config = configuration(contract, source)
    write(work/'batch-bootstrap.py',source.encode())
    write(work/'job.json',encoded(config)+b'\n')
    write(work/'batch-expected.json',encoded({'contract':contract,'job_config_sha256':digest(work/'job.json')})+b'\n')
    return contract


def check(work):
    package, manifest, image = admission(work)
    expected = read(work/'batch-expected.json')
    contract = expected['contract']
    for field,value in {'version':2,'project_id':PROJECT,'project_number':'918355398369','region':REGION,
            'run_id':manifest['run_id'],'image':image['image_uri'],'build_sha256':image['build_sha256'],
            'package_sha256':image['package_sha256'],'engine_revision':manifest['engine_revision'],
            'numerical_revision':manifest['numerical_revision']}.items():
        require(contract[field] == value,'handoff contract mismatch: '+field)
    pin=contract['input']
    require(pin['bucket']==BUCKET and pin['object']=='inputs/'+manifest['run_id']+'/request.json' and
            str(pin['generation']).isdigit() and int(pin['generation'])>0 and
            pin['sha256']==manifest['input_sha256'],'input pin mismatch')
    source=Path(__file__).with_name('batch_bootstrap.py')
    require(digest(source)==contract['bootstrap_sha256'] and (work/'batch-bootstrap.py').read_bytes()==source.read_bytes(),
            'reviewed startup source changed')
    config=configuration(contract,source.read_text())
    require(read(work/'job.json')==config and digest(work/'job.json')==expected['job_config_sha256'],'job changed')
    return contract, config


def verify_job(actual, config, contract):
    normalized=copy.deepcopy(actual)
    for k in ('name','uid','createTime','updateTime','status'):normalized.pop(k,None)
    groups=normalized.get('taskGroups',[])
    require(len(groups)==1,'wrong task groups')
    groups[0].pop('name',None)
    # Protobuf JSON omits default zero retry count; submit explicitly sets zero.
    groups[0]['taskSpec'].setdefault('maxRetryCount',0)
    policy=normalized['allocationPolicy']
    labels=policy.get('labels',{})
    if 'batch-job-id' in labels:
        require(labels.pop('batch-job-id')==contract['run_id'],'wrong generated job label')
    locations=policy['location']['allowedLocations']
    require('regions/'+REGION in locations and all(x=='regions/'+REGION or
            x in ['zones/'+REGION+'-'+z for z in ('b','c','d')] for x in locations),'wrong allocation region')
    policy['location']['allowedLocations']=['regions/'+REGION]
    require(normalized==config,'actual job configuration mismatch')
    identity={'contract':contract,'job_name':actual['name'],'batch_uid':actual['uid']}
    validate_identity(identity,contract)
    return identity


def submit(work):
    contract,config=check(work)
    write(work/'batch-submit-intent.json',encoded({'job_config_sha256':digest(work/'job.json')})+b'\n')
    for path in (work/'batch-submit-intent.json',work):
        fd=os.open(path,os.O_RDONLY)
        try:os.fsync(fd)
        finally:os.close(fd)
    raw=gcloud('batch','jobs','submit',contract['run_id'],'--location='+REGION,'--config='+str(work/'job.json'),'--format=json')
    write(work/'batch-submit.json',raw)
    identity=verify_job(json.loads(raw),config,contract)
    write(work/'job-identity.json',encoded(identity)+b'\n')
    uri='gs://'+BUCKET+'/control/'+contract['run_id']+'/job-identity.json'
    gcloud('storage','cp',str(work/'job-identity.json'),uri,'--if-generation-match=0')
    meta=gcloud('storage','objects','describe',uri,'--format=json')
    write(work/'job-identity-object.json',meta)
    generation=str(json.loads(meta)['generation'])
    require(generation.isdigit() and int(generation)>0,'invalid control generation')
    gcloud('storage','cp',uri+'#'+generation,str(work/'job-identity-downloaded.json'))
    require((work/'job-identity-downloaded.json').read_bytes()==(work/'job-identity.json').read_bytes(),'control upload differs')
    return {'status':'BATCH_UID_HANDED_OFF','job_name':identity['job_name'],
            'batch_uid':identity['batch_uid'],'control_generation':generation}


def verify_result(work, artifacts, completion, actual_job):
    contract, config = check(work)
    identity = verify_job(actual_job, config, contract)
    require(actual_job['status']['state'] == 'SUCCEEDED', 'actual Batch job must succeed')
    require(identity == read(work/'job-identity.json'), 'submitted/actual Batch UID differs')
    require(completion['batch_uid'] == identity['batch_uid'], 'completion Batch UID differs')
    expected = {k: contract[k] for k in ('run_id','input','image','build_sha256','engine_revision','numerical_revision')}
    accepted = verify_download(artifacts, completion, expected)
    require('operator-job-identity.json' in [f['path'] for f in completion['files']], 'missing checksummed operator identity')
    record = read(artifacts/'operator-job-identity.json')
    require(record == {'identity':identity, 'object':'control/'+contract['run_id']+'/job-identity.json',
                       'generation':str(read(work/'job-identity-object.json')['generation'])},
            'worker control identity/generation differs')
    return accepted


def main():
    p=argparse.ArgumentParser(description=__doc__)
    sub=p.add_subparsers(dest='command',required=True)
    a=sub.add_parser('prepare');a.add_argument('--work',type=Path,required=True)
    a.add_argument('--metadata',type=Path,required=True);a.add_argument('--downloaded',type=Path,required=True)
    a=sub.add_parser('submit');a.add_argument('--work',type=Path,required=True)
    a=p.parse_args()
    result=prepare(a.work,a.metadata,a.downloaded) if a.command=='prepare' else submit(a.work)
    print(json.dumps(result,sort_keys=True))


if __name__=='__main__':main()
