# Plan

Selected design is implemented in a separate Angular entrypoint and connected through the existing console API server. The original console remains available at port 4321 from More. No financial or engine logic is duplicated.

Current delivery uses local retained kind services, explicit run-scoped projection contracts with a labelled legacy fallback, gateway commands and NATS prices. Cloud archives are disabled. Runtime owner is the standalone console source, not a generated override.

Next: initialize the retained rig's account admission under an explicit operator workflow; deploy current compatible service versions with a recovery plan before enabling advanced order types; complete trader authentication/account authorization and remaining per-tool migration. Cloud rollout is separately authorized.
