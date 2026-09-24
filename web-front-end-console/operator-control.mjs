// Run activation/selection and projection recovery are operator maintenance (RI-06/RI-07 O1):
// trade-processor /v2/projection-control and /v2/projection-recovery, and the gateway's
// /run/control. The console never offers them, and its proxies refuse them so a browser cannot
// reach them through it either — the server attaches an internal JWT to every
// /trade-processor path, and the gateway's dev risk-control default is in the page source.
// Matched on a normalised path (decoded, case-folded, slashes collapsed) because the raw URL is
// what gets forwarded upstream.
export function isOperatorControl(rawUrl) {
  let p = String(rawUrl ?? '').split(/[?#]/)[0];
  for (let i = 0; i < 3; i++) { try { const d = decodeURIComponent(p); if (d === p) break; p = d; } catch { return true; } }
  p = p.replace(/\\/g, '/').replace(/\/+/g, '/').toLowerCase();
  // `/legacy/…` is forwarded to the edge with the prefix stripped; `/gw/<n>/…` reaches one gateway
  // pod at its own root. Both would otherwise walk around a prefix match.
  p = p.replace(/^\/legacy(?=\/)/, '');
  return /^\/trade-processor\/v2\/projection-(control|recovery)(\/|$)/.test(p)
    || /^(\/order-matcher|\/gw\/\d+)\/run\/control(\/|$)/.test(p);
}
export const OPERATOR_CONTROL_REFUSAL = { code: 'operator_control_refused',
  error: 'run selection and activation are operator-only and are not reachable through the console' };
