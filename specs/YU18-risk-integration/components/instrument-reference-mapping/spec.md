# Instrument reference mapping specification

Date: 2026-10-07. Owner: codex-reference-mapping. Status: documentation implemented; local checks and coordinator review recorded in tasks.

FR-IRM-01: Read primary TreasuryDirect/OCC explanatory documents and record titles, URLs, retrieval dates, versions/unknown effective dates and section locators. Distinguish generic definitions from particular security terms.

FR-IRM-02: Map the relevant bill/note and listed-option fields, units and transformations to exact authoritative reference/export/terms and pinned engine owners. Enumerate unsupported and unknown fields; distinguish the structural CSV/terms boundary from narrower accepted consumer profiles.

FR-IRM-03: Deliver four explicitly synthetic illustrations: bill, fixed-coupon note, standard option and adjusted counterpart. Existing Treasury fixtures retain their original bytes; examples distinguish contracts, premium factors and deliverable identity/quantities. No illustration is called an observed quote, engine result or financial validation.

FR-IRM-04: Record human/Alex decisions without choosing new conventions or weakening schemas. Publication, automated acquisition, financial implementation and shared index edits require their own assigned work.

NFR-IRM-01: Documentation and synthetic data only. No engine/pricing execution, runtime/schema edits, vendor datasets, real portfolio data, paid compute, cloud or canonical staging mutation.

SC-IRM-01: Existing bundle/terms validators accept the referenced bill/note fixtures and copied field representations. Existing container allowlist accepts original bill and refuses note; historical provisional allowlist remains distinct.

SC-IRM-02: Existing CSV parser can read the illustrative option rows but existing terms validator refuses OPTION. Pure Java/Node parsers distinguish standard letter-only root and unsupported numeric counterpart. A structural acceptance is not a faithful adjusted contract.

SC-IRM-03: Component/frontmatter/root/readiness/spec-coverage gates, changed-file link checks and diff checks succeed or record precise limitations. Source/schema hashes and command outcomes accompany the delivery.

Acceptance is a local documentation/structural milestone. No generated execution, cluster behavior, clearing/settlement lifecycle or numerical pricing is claimed.
