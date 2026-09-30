// The workspace authorizes the account. The registry selects the projection, while the
// receiving gateway fences the immutable descriptor even if routing changes after this read.
// Match RestingOrder.isOpen and the console's LIVE_STATUSES: suspension is reversible.
const live = new Set(['NEW','PARTIALLY_FILLED','PENDING_TRIGGER','SUSPENDED']);
export async function deskOrderAction(upstream, accounts, action, body) {
  const refuse=(status,error)=>({status,body:{error}});
  if (!['orders','cancel','replace'].includes(action)) return refuse(404,'Unknown order action.');
  if (!Number.isSafeInteger(body.accountId) || !accounts.includes(body.accountId)) return refuse(403,'This account does not belong to this workspace.');
  if (typeof body.projectionScope !== 'string' || !body.projectionScope) return refuse(409,'A confirmed current run is required.');
  const read=url=>upstream(url,{method:'GET'});
  const [selected,registry,status]=await Promise.all([
    read('/position-service/v2/projections/active'),read('/position-service/v2/projections'),read('/order-matcher/run/status')]);
  if (selected.status!==200 || registry.status!==200 || status.status!==200 || !Array.isArray(registry.body)) return refuse(503,'Current run could not be confirmed. Nothing sent.');
  const scope=body.projectionScope, run=registry.body.find(r=>r.projection_scope===scope);
  if (!run || selected.body?.projectionScope!==scope || run.phase!=='ACTIVE' || !run.descriptor_hash ||
      status.body?.projectionScope!==scope || status.body?.descriptorHash!==run.descriptor_hash || status.body?.runPhase!==2)
    return refuse(409,'Selected run is not the active trading run. Nothing sent.');
  let payload;
  if(action==='orders') {
    payload={...body};delete payload.projectionScope;
  } else {
    if (!Number.isSafeInteger(body.orderRef) || body.orderRef<=0) return refuse(400,'A positive order reference is required.');
    const rows=await read(`/trade-processor/v2/projections/${encodeURIComponent(scope)}/accounts/${body.accountId}/orders?status=all`);
    if(rows.status!==200 || !Array.isArray(rows.body)) return refuse(503,'Orders could not be confirmed. Nothing sent.');
    const id=`${run.cluster_epoch}-${body.orderRef}`;
    const order=rows.body.find(o=>(o.id??o.orderId)===id && Number(o.accountId)===body.accountId && o.projectionScope===scope);
    if(!order || !live.has(order.status)) return refuse(409,'The selected account has no working order with this identity. Nothing sent.');
    payload={orderRef:body.orderRef};
    if(action==='replace') {
      if(order.orderType!=='LIMIT') return refuse(422,'This editor supports working Limit orders only.');
      if(!Number.isSafeInteger(body.quantity) || body.quantity<=Number(order.quantity)-Number(order.remainingQuantity) ||
         typeof body.limitPrice!=='number' || !Number.isFinite(body.limitPrice) || body.limitPrice<=0)
        return refuse(422,'Quantity must exceed filled quantity and limit price must be positive.');
      payload={...payload,quantity:body.quantity,limitPrice:body.limitPrice,clientOrderId:body.clientOrderId};
    }
  }
  const again=await read('/position-service/v2/projections/active');
  if(again.status!==200 || again.body?.projectionScope!==scope) return refuse(409,'Active run changed. Nothing sent.');
  payload.expectedDescriptorHash=run.descriptor_hash;
  payload.expectedProjectionScope=scope;
  // No retry: a timeout can follow a committed command.
  try {return await upstream(`/order-matcher/${action}`,{method:'POST',body:payload});}
  catch {return refuse(504,'Outcome unknown. Check orders before sending another request.');}
}

export function deskProxyTarget(rawUrl) {
  let path=String(rawUrl??'').split(/[?#]/)[0];
  for(let i=0;i<3;i++){try{const decoded=decodeURIComponent(path);if(decoded===path)break;path=decoded;}catch{return {invalid:true};}}
  path=path.replace(/\\/g,'/').replace(/\/+/g,'/').toLowerCase().replace(/^\/legacy(?=\/)/,'');
  const mutation=/^(?:\/order-matcher|\/gw\/\d+)\/(?:orders|cancel|replace|trades)(?:\/|$)/.test(path);
  const account=path.match(/\/accounts\/(\d+)\/(?:orders|trades|positions)\/?$/) || path.match(/^\/position-service\/(?:positions|trades)\/(\d+)\/?$/);
  return {mutation,account:account?Number(account[1]):null};
}
