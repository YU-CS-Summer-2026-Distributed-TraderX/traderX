# ADR 075: Completed-export receipts bridge the local producer and coordinator

Status: accepted for local YU18 integration on traderX-risk-integration.

The readiness payload already names both CSV artifacts, their individual hashes/counts, the shared
cut and the quiescence witness. Reuse that contract instead of guessing that two similarly named
files are complete. The producer builds the payload in a shared helper and optionally publishes it
to a private local directory after both artifact writes. NATS notification behavior remains available.

An opt-in local receipt avoids making a best-effort notification the only path to discovery. The
bridge scans receipts for an explicit business date, verifies the original bytes and metadata, then
publishes a content-addressed bundle. It does not close a session, supply prices, fetch remote data,
authenticate producer identity or infer the cluster epoch. One receipt directory must belong to one
epoch; date and valuation context are explicit at invocation.

Receipt publication uses a temporary file plus an atomic, no-overwrite hard link in the same local
filesystem. This supports Linux/macOS local volumes with POSIX permissions and hard links. Shared
filesystems, distributed workers and power-loss durability have not been proved. Failed/incomplete
producer files without a final receipt cannot enter the bridge. Existing receipt bytes are immutable.

The proof exercises real orders/bookings through MatchingEngineClusteredService.onSessionMessage,
then the production cut/CSV renderers and completion helper, all in-process. It verifies actual
export-to-consumer compatibility while keeping the live cluster and EOD orchestration outside the
claim. It exposed and corrected two fixture assumptions: zero-coupon schedule/accrual fields are
empty, and the SOFR index is separate from the named full convention.
