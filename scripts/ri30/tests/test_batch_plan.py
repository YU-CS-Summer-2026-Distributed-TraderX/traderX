import pytest
from batch_plan import plan


def test_pilot_boundaries_and_identity():
    image = 'registry/ri30@sha256:' + 'a' * 64
    job = plan(image, 'gs://destination/inputs/pilot.json', 123, 'b' * 64, 'worker@example.com')
    group = job['taskGroups'][0]; spec = group['taskSpec']
    assert group['taskCount'] == group['parallelism'] == 1
    assert spec['maxRetryCount'] == 0 and spec['maxRunDuration'] == '7200s'
    env = spec['environment']['variables']
    assert env['WORKER_IMAGE'] == image and env['INPUT_MANIFEST_OBJECT_COUNT'] == '1'
    assert env['DESTINATION_BUCKET'] == 'project-compsci-484414-taq-parquet'
    assert spec['volumes'][0]['deviceName'] == job['allocationPolicy']['instances'][0]['policy']['disks'][0]['deviceName']
    with pytest.raises(ValueError):
        plan('registry/ri30:latest', 'gs://destination/m.json', 123, 'b' * 64, 'worker@example.com')


def test_campaign_is_one_sequential_worker():
    job=plan('registry/ri30@sha256:'+'a'*64,'gs://dest/manifest',123,'b'*64,'worker@example.com',campaign=True)
    g=job['taskGroups'][0];s=g['taskSpec']
    assert g['taskCount']==g['parallelism']==1
    assert s['maxRunDuration']=='28800s' and s['maxRetryCount']==0
    assert s['environment']['variables']['INPUT_MANIFEST_OBJECT_COUNT']=='3'
    assert s['runnables'][0]['container']['commands']==['/app/campaign.py']


def test_full_run_caps_workers_and_keeps_one_archive_per_task():
    args=('registry/ri30@sha256:'+'a'*64,'gs://dest/manifest',123,'b'*64,'worker@example.com')
    job=plan(*args,object_count=132,parallelism=32)
    g=job['taskGroups'][0]
    assert (g['taskCount'],g['parallelism'],g['taskCountPerNode'])==(132,32,1)
    assert g['taskSpec']['environment']['variables']['INPUT_MANIFEST_OBJECT_COUNT']=='132'
    assert g['taskSpec']['maxRetryCount']==0
    assert 'commands' not in g['taskSpec']['runnables'][0]['container']
    for kwargs in ({'object_count':132,'parallelism':33},{'object_count':1,'parallelism':2},{'object_count':0},{'campaign':True,'object_count':132}):
        with pytest.raises(ValueError): plan(*args,**kwargs)
