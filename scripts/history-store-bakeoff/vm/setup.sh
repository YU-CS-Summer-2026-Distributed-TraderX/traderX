#!/bin/bash
# setup.sh feed|store: one-time host prep on a fresh debian-12 bake-off VM (run as root).
set -euo pipefail
ROLE=$1
apt-get update -qq && apt-get install -y -qq docker.io openjdk-17-jdk-headless sysstat iproute2 >/dev/null
DEV=$(readlink -f /dev/disk/by-id/google-data)   # attached with device-name=data
mkfs.ext4 -q -F -E lazy_itable_init=0,lazy_journal_init=0 "$DEV"
mkdir -p /data && mount -o noatime "$DEV" /data && chmod 777 /data
IF=$(ip -o route get 10.128.0.1 | awk '{for(i=1;i<=NF;i++) if($i=="dev") print $(i+1)}')
# Cross-VM latency: 0.5 ms on every egress packet of the data NIC (both VMs, so RTT ~1 ms).
tc qdisc replace dev "$IF" root netem delay 0.5ms limit 1000000
tc qdisc show dev "$IF"
if [ "$ROLE" = feed ]; then
  mkdir -p /data/nats
  docker run -d --name nats --network host -v /data:/data -v /opt/hsb/vm/nats.conf:/etc/nats.conf:ro \
    nats:2.14-alpine -c /etc/nats.conf
fi
echo "setup $ROLE done: data=$DEV if=$IF"
