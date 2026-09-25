"""Verify actual framed NATS notifications, binding subject to its own JSON payload."""
import json
import sys
from pathlib import Path

def verify(raw):
    messages=[]
    while raw:
        line,sep,raw=raw.partition(b"\r\n")
        assert sep, "truncated NATS header"
        if not line.startswith(b"MSG "): continue
        fields=line.split(); size=int(fields[-1]); subject=fields[1].decode()
        assert len(raw)>=size+2 and raw[size:size+2]==b"\r\n", "truncated NATS payload"
        payload_bytes=raw[:size];raw=raw[size+2:]
        if subject.startswith('/v2/projections/'):
            body=json.loads(payload_bytes)
            assert body['topic']==subject
            payload=body['payload'];parts=subject.split('/')
            assert payload['projectionScope']==parts[3]
            assert str(payload['accountId'])==parts[5]
            messages.append((subject,payload))
    assert messages, "no managed notifications"
    for scope,account,trade,quantity in [('fresh-live',22214,'e1-freshlive-3-B',50),
                                      ('fresh-live',11413,'e1-freshlive-4-S',-50),
                                      ('next-live',22214,'e1-nextlive-1-B',7),
                                      ('next-live',11413,'e1-nextlive-2-S',-7)]:
        prefix=f'/v2/projections/{scope}/accounts/{account}'
        assert any(s==prefix+'/trades' and p['id']==trade for s,p in messages), trade
        assert any(s==prefix+'/positions' and p['quantity']==quantity for s,p in messages), (scope,account,quantity)
    return {'managedMessages':len(messages),'subjectPayloadBinding':True,'expectedTradeAndPositionChecks':8}

if __name__=='__main__':
    print(json.dumps(verify(Path(sys.argv[1]).read_bytes()),indent=2))
