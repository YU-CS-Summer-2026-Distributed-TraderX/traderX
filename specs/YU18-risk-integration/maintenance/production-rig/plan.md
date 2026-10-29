# Production rig plan

1. Render the parity tiers and the drain settings in `prepare.py`, with `validate()` refusing a candidate without
   them (FR-PRG-05/06/07).
2. Replace the gateway's three concurrent shutdown hooks with one ordered drain; prove the FIX half with two replicas
   on one JetStream store, with a negative control (FR-PRG-07).
3. Write `rig.sh` around the restore gate: gateways at 0 until G0–G4 hold (FR-PRG-01..04). Test it offline with
   stubbed kubectl and gcloud, including a refusal for every check and a mutation that removes the gate.
4. Write `gateways.sh` for grow and shrink, with exact node removal and FIX resume timing (FR-PRG-06), and the HPA
   and pool autoscaler (FR-PRG-09).
5. Live, on a rehearsal namespace first, then on production in 5a with yaakov's approval: `pools`, `up` (gate on
   the backport build), `gateways.sh` up and down with FIX clients connected through the internal LB, autoscale up
   under load and back down, `down` to zero instances.
