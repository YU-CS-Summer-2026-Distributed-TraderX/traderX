// Authorization and forwarding must see the same path. Reject syntax another HTTP
// layer could reinterpret, rather than attaching a service credential to it.
export function canonicalRequestTarget(raw) {
  if (typeof raw !== 'string' || !raw.startsWith('/') || raw.startsWith('//')) throw Error('Invalid target');
  const q = raw.indexOf('?');
  const search = q < 0 ? '' : raw.slice(q);
  let pathname = q < 0 ? raw : raw.slice(0, q);
  for (let i = 0; i < 8 && pathname.includes('%'); i++) pathname = decodeURIComponent(pathname);
  if (/[%;?#\x00-\x20\x7f]/.test(pathname)) throw Error('Ambiguous path');
  pathname = pathname.replace(/\\/g, '/').replace(/\/+/g, '/');
  pathname = new URL(pathname, 'http://console.invalid').pathname;
  return pathname + search;
}

export const edgePath = p => p.replace(/^\/legacy(?=\/)/i, '');
