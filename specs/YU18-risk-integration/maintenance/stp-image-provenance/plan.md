# STP image provenance plan

Status: implemented locally, ready for coordinator review after validation. No self-integration.

The operative sources are the repository proof and builder scripts. The generated Dockerfile is inherited from YU12, while YU18 order-matcher overrides contain the current STP ownership-group branch and gateway. The new YU18 revert patch removes that branch/method and the replace registration in a private copy. The legacy patch keeps its existing behavioral removals; its final gateway context line is narrowed to allow the inherited futures route with zero fuzz. Generation inputs remain at the state parent.

The helper inventories the entire effective context, fingerprints conservative composition inputs, snapshots both roles, transforms pre, compiles both with one specialized recipe, pins base references and adds provenance during Docker build. It verifies output bytes by copying classes/lib from stopped containers addressed by immutable inspected image identity. Proof admission uses the same helper before any rig calls. The generic cluster-image builder remains untouched.

Validation lanes:

1. Original proof with offline process fixtures demonstrates both timestamp defects before the fix.
2. Offline actual builder/proof controls exercise admission and dispatch, including role, malformed data, changed input and artifact negatives.
3. Full isolated YU18 generation and strict actual-source pre transformation establish owner/composition applicability; repeated rendering checks byte identity.
4. Optional disposable scratch image exercises real Docker labels, cached config/layers and file-copy verification, with synthetic compilation explicitly labeled.
5. Component checks, shell/Python syntax and required repository spec gates.

Dependencies: RI-17 consumes the unchanged builder/tag interfaces; RI-20 supplies broader deployment/node provenance later. Live behavioral and historical recovery proofs require separate rig authorization. Coordinator reconciles additive state/backlog indexes and integration; this lane edits neither shared index nor peer runner.
