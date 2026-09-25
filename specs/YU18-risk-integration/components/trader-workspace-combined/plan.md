# Plan

Selected design is implemented in a separate Angular entrypoint and connected through the existing console API server. The original console remains available at port 4321 from More. No financial or engine logic is duplicated.

Current delivery uses local retained kind services, explicit run-scoped projection contracts with a labelled legacy fallback, gateway commands and NATS prices. Cloud archives are disabled. Runtime owner is the standalone console source, not a generated override.

The authorized fresh local YU18 rig now advertises typed-order capability, admits the eight approved demo accounts and passes 31 live order-type cases. Existing-order mutations still require managed run identity. Next: complete trader authentication/account authorization and remaining per-tool migration. Cloud rollout is separately authorized.
