# The manifests pin a build the rig no longer runs — and a fresh apply silently sheds today's fixes

> Facts measured 2026-08-24 ~14:45Z. The tags will drift further; re-derive with the commands at the
> bottom before acting. Filed as a record of the gap — the fix is a decision about how images get
> pinned at all, and that is yaakov's to make, not a lane's.

## What pins what vs what runs (kind rig, YU17 layer)

| consumer | manifest pins (`specs/YU17-otc-rates/.../cluster/`) | rig actually runs |
|---|---|---|
| `order-matcher-cluster` (members) | `traderx/cluster-node:yu17-bbo` ([statefulset.yaml:68](../../specs/YU17-otc-rates/generation/kubernetes/cluster/statefulset.yaml)) | `:yu17-markwait2` |
| `cluster-gateway` | `:yu17-bbo` (gateway.yaml:52) | `:yu17-auditreason` |
| `feed-adapter` | `:yu17-bbo` (feed-adapter.yaml:49) | `:yu17-bbo` (agrees) |
| `risk-extract` | `:yu17-bbo` (risk-extract.yaml:55) | `:yu17-bbo` (agrees) |

Every earlier layer has the same shape with its own tags (`:yu12` … `:yu16-ackB`): lanes roll new
builds with `kubectl set image` under fresh suffix tags, and the manifest pins never move.

## What reverts on a fresh `kubectl apply` / bring-up, and what does not

- **Does NOT revert:** the 2026-08-24 liveness-probe fix (tcpSocket, timeouts) — it lives in the
  manifest itself, which is committed.
- **DOES revert, silently:** everything Java-side since `:yu17-bbo` was built. On the members that
  is the mark-file bounded-retry fix — so the first post-apply restart races
  `active mark file detected` again and crash-loops 2–3× per kill, the overnight behaviour back
  with no signal. On the gateway it is the `auditreason` work (regulatory refusal reasons).

## How you would know (none of it is loud)

- `kubectl get statefulset order-matcher-cluster -o jsonpath='{.spec.template.spec.containers[0].image}'`
  disagreeing with what the last lane's report says it rolled — requires knowing to ask.
- After any member restart: the log has `Waiting for mark file release` on a fixed build; a
  recurring `active mark file detected` crash in `--previous` logs means the reverted one.
- The general method for identifying which build a cluster-node image actually is (in-image probes
  give false zeros) is in memory: discriminate against the branch's source history, not another
  image.

## Why this is filed rather than fixed

Updating the four pins to today's tag repeats the pattern one tag later — the next lane's
`set image` re-opens the gap. The durable fix is a policy: either the manifests are the authority
(then rolling = editing the manifest, and `set image` is banned), or the build script's derived
tag is (then the manifests should pin the derived tag and bring-up should build it). That choice
touches every layer's manifests and every lane's workflow — yaakov's call.
