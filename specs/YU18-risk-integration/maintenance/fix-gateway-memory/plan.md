# FIX gateway memory plan

The gateway's own state was already capped, and in-memory sessions store nothing for resend. What was not capped
was what a client's pace decides: answers it has not read yet, and orders it sent faster than they could be
answered. QuickFIX/J has a knob for each: a slow-consumer limit on queued writes, and inbound watermarks that
suspend socket reads. Setting them turns both into a count per session. The JetStream adoption path read a
whole day into memory at takeover; it now streams and keeps only open orders. See ADR-092.
