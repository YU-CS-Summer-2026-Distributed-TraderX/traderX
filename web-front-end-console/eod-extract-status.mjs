export function remoteExtractStatus(pod, sink, verdict) {
  if (verdict?.state === 'ok') return {state:'ok', pod, sink, files:[], version:verdict.version,
    detail:`v${verdict.version} cut produced and announced by the extract service; stored directly in the bucket`};
  return {state:'remote', pod, sink, files:[],
    detail:`The sink is ${sink}; cuts go directly to the bucket. No completed cut was observed in the extract service log for this date. See the published stage for stored artifacts.`};
}
