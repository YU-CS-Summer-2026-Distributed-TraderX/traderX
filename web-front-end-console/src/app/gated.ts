import { Component, inject } from '@angular/core';
import { Api } from './api';

/**
 * Marks an action the SERVER refuses without a sign-in, so the operator learns it before clicking
 * rather than from a 401 afterwards.
 *
 * Put this only beside an action that is actually gated — a mark on an open endpoint is a lock
 * attached to nothing, which teaches the reader to distrust the ones that are real. The four it is
 * used on were each verified to answer 401 anonymously: force settle, cancel, orphan sweep, and
 * algo order creation. Notably NOT plain order entry, which stays open on both the trading page and
 * the session driver — gating it here alone would fence one door of an open field.
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
