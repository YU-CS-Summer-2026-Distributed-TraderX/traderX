import { Component, inject } from '@angular/core';
import { Api } from './api';

/**
 * Marks an action the SERVER refuses without a sign-in, so the operator learns it before clicking
 * rather than from a 401 afterwards.
 *
 * Put this only beside an action that is actually gated — a mark on an open endpoint is a lock
 * attached to nothing, which teaches the reader to distrust the ones that are real. The three it is
 * used on were each verified to answer 401 `admin_auth_required` anonymously: force settle (twice)
 * and the orphan sweep.
 *
 * The line the server draws is that **an override departs from what the system would have done by
 * itself** — force-settle jumps a trade past its settlement cycle, the sweep rewrites reconciliation
 * state. Cancelling your own order and scheduling a TWAP are ordinary trading and are NOT gated, so
 * they carry no mark; nor does plain order entry, on the ticket or in the session driver. This list
 * shrank from four when algo-create was opened — if it moves again, re-probe rather than assume,
 * because a stale mark and a missing one fail in opposite directions.
 *
 * The action is left ENABLED. The server is the control, this is a label, and a disabled button
 * whose endpoint is open would lie in the other direction.
 */
@Component({
  selector: 'gated',
  template: `
    @if (!api.authUser()) {
      <button type="button" class="lock" (click)="api.authPrompt.set(true)"
        title="The server refuses this change without a sign-in. Reads stay open.">
        needs sign-in</button>
    }
  `,
  styles: `
    :host { display: inline-flex; }
    .lock { font-size: 10.5px; padding: 0 6px; line-height: 17px; border-radius: 9px;
            color: var(--warn); background: var(--warn-soft); border: 1px solid transparent; }
    .lock:hover { border-color: var(--warn); }
  `,
})
export class Gated {
  readonly api = inject(Api);
}
