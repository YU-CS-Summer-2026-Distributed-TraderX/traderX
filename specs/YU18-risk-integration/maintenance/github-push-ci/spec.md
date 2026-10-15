# Requirements

- CI-FIX-01: The YU18 standalone MariaDB initial schema defines the settlement column before creating its ordered index, initializes nonempty synthetic history, and completes the existing RI06 migration. Retained migration preservation and repeatability assertions remain enabled.
- CI-FIX-02: The installed risk-status layer provides Python >=3.10, SQLite and a venv before loading the pinned result adapter. The actual installed EOD suite and synthetic Node/Python status bridge must pass without runtime source mounts or network access.
- CI-FIX-03: Preserve the exact failed logs/reports, reproduce both failures locally, regenerate authoritative outputs, and report attributable executed counts and skips. Execute the new standalone-schema test against the original missing-column defect as a negative control.
- CI-FIX-04: Preserve canonical dirty/private files and staging. No pushes, external engine edits, cloud effects or canonical integration are included. Hosted acceptance follows a user push of approved integrated changes.
