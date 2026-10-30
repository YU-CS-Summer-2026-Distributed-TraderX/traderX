#!/bin/bash
# sample.sh OUT: every 2 s, the cgroup of each running bake-off container (store, hdb): cpu usec, memory current/
# peak/anon/file, plus the data dir size every 30 s. Peak CPU = max delta of usage_usec over the interval.
OUT=$1; i=0
echo "epoch,container,cpu_usec,mem_current,mem_peak,anon,file,data_bytes" > "$OUT"
while true; do
  for c in store hdb; do
    id=$(docker inspect -f '{{.Id}}' $c 2>/dev/null) || continue
    cg=/sys/fs/cgroup/system.slice/docker-$id.scope
    [ -d "$cg" ] || continue
    cpu=$(awk '/usage_usec/ {print $2}' $cg/cpu.stat)
    anon=$(awk '$1=="anon" {print $2}' $cg/memory.stat); file=$(awk '$1=="file" {print $2}' $cg/memory.stat)
    du=""; [ $((i % 15)) -eq 0 ] && du=$(du -sb /data/$(cat /opt/hsb/current-sink 2>/dev/null) 2>/dev/null | cut -f1)
    echo "$(date +%s),$c,$cpu,$(cat $cg/memory.current),$(cat $cg/memory.peak 2>/dev/null),$anon,$file,$du" >> "$OUT"
  done
  i=$((i+1)); sleep 2
done
