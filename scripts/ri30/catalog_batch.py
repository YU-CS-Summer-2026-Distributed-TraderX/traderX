"""Render compaction Batch plan with exact frozen input, <=32 workers and cloud scratch."""
import re
import json
from catalog_cloud import BUCKET

def plan(image,ref,count,parallelism,phase='pilot'):
    if not 1<=parallelism<=min(count,32) or count<1: raise ValueError('invalid worker/task bound')
    if not re.fullmatch(r'[^\s]+@sha256:[a-f0-9]{64}',image): raise ValueError('unpinned image')
    if not re.fullmatch('[a-f0-9]{64}',ref['sha256']) or ref['generation']<=0: raise ValueError('unpinned input')
    if phase not in ('pilot','full'): raise ValueError('unknown phase')
    return dict(taskGroups=[dict(taskCount=count,parallelism=parallelism,taskCountPerNode=1,taskSpec=dict(
        runnables=[dict(container=dict(imageUri=image,volumes=['/mnt/disks/ri30-catalog:/scratch']))],
        environment=dict(variables=dict(WORKER_IMAGE=image,GOOGLE_CLOUD_PROJECT='project-compsci-484414',
                                       COMPACTION_INPUT_REF=json.dumps(ref),SCRATCH_DIR='/scratch')),
        computeResource=dict(cpuMilli=4000,memoryMib=16384),maxRetryCount=0,maxRunDuration='86400s',
        volumes=[dict(deviceName='ri30-catalog',mountPath='/mnt/disks/ri30-catalog')]))],
        allocationPolicy=dict(instances=[dict(policy=dict(machineType='e2-standard-4',provisioningModel='STANDARD',
           disks=[dict(deviceName='ri30-catalog',newDisk=dict(sizeGb=250,type='pd-balanced'))]))],
           location=dict(allowedLocations=['regions/us-east1']),serviceAccount=dict(email='taq-parquet-worker@project-compsci-484414.iam.gserviceaccount.com'),
           labels=dict(task='ri30',phase='catalog-'+phase)),
        logsPolicy=dict(destination='CLOUD_LOGGING'),labels=dict(task='ri30',phase='catalog-'+phase))
