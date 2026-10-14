"""Bounded three-archive pilot, then exact daily/monthly comparison; fail fast."""
import json
import os
import worker
import compare

if __name__ == '__main__':
    if int(os.environ['INPUT_MANIFEST_OBJECT_COUNT']) != 3:
        raise ValueError('three-archive pilot requires exactly three frozen inputs')
    for i in range(3):
        os.environ['BATCH_TASK_INDEX'] = str(i)
        print(json.dumps({'event':'source_start','index':i}),flush=True)
        worker.run()
    print(json.dumps({'event':'comparison_start'}),flush=True)
    compare.run()
