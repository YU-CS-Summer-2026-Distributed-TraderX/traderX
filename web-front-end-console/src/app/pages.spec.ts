import { TestBed } from '@angular/core/testing';
import { signal, NO_ERRORS_SCHEMA } from '@angular/core';
import { Api } from './api';
import { AdminPage, ReplayPage } from './pages';

/**
 * The session driver is signed-in only.
 *
 * Worth a committed test because a gate that does not gate looks exactly like one that does. The
 * card renders in both states and only its CONTENTS differ, so nothing on screen distinguishes a
 * working guard from a removed one.
 *
 * Asserted in BOTH directions on purpose. "The panel is absent when signed out" alone would pass
 * just as happily against a page that never renders the panel at all — which is a broken page, not
 * a working gate.
 *
 * The child panels are deliberately NOT rendered (NO_ERRORS_SCHEMA leaves their elements inert).
 * The subject here is AdminPage's own conditional; a first draft that let the real panels render
 * failed on `api.bandsState is not a function` — i.e. it failed for a sibling component's reasons,
 * which would make it a test that goes red when something unrelated changes.
 *
 * Scope: this asserts the UI guard only. The server does NOT gate plain order entry (see gated.ts),
 * so this says nothing about whether the same orders could be posted directly, and must not be read
 * as if it did.
 */
describe('AdminPage — the session driver is behind sign-in', () => {
  const authUser = signal<string | null>(null);

  const render = () => {
    const f = TestBed.createComponent(AdminPage);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    return { panel: el.querySelector('demo-session'), text: el.textContent ?? '' };
  };

  beforeEach(() => {
    authUser.set(null);
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: { authUser, authPrompt: signal(false) } }],
      schemas: [NO_ERRORS_SCHEMA],
    });
    TestBed.overrideComponent(AdminPage, { set: { imports: [], schemas: [NO_ERRORS_SCHEMA] } });
  });

  it('withholds the panel and offers a sign-in when signed out', () => {
    const { panel, text } = render();
    expect(panel).toBeNull();
    expect(text).toContain('Sign in to use it');
  });

  it('renders the panel once signed in', () => {
    authUser.set('admin');
    const { panel, text } = render();
    expect(panel).not.toBeNull();
    expect(text).not.toContain('Sign in to use it');
  });
});

/**
 * The whole Replay tab is signed-in only, and for a different reason from the session driver above:
 * that gate stops an accident, this one is the display-rights gate. Our permission over the TAQ
 * corpus covers USE; whether it covers DISPLAY is ADR-068 open question 1 and still open. So the
 * surface that exists to show those prices is the one surface that asks who is looking.
 *
 * Asserted in both directions for the same reason as above: "absent when signed out" alone passes
 * against a page that renders nothing at all.
 */
describe('ReplayPage — the tape is behind sign-in', () => {
  const authUser = signal<string | null>(null);

  const render = () => {
    const f = TestBed.createComponent(ReplayPage);
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    return { clock: el.querySelector('replay-clock'), tape: el.querySelector('replay-tape'),
             collar: el.querySelector('collar-reference'), text: el.textContent ?? '' };
  };

  beforeEach(() => {
    authUser.set(null);
    TestBed.configureTestingModule({
      providers: [{ provide: Api, useValue: { authUser, authPrompt: signal(false) } }],
      schemas: [NO_ERRORS_SCHEMA],
    });
    TestBed.overrideComponent(ReplayPage, { set: { imports: [], schemas: [NO_ERRORS_SCHEMA] } });
  });

  it('shows no tape and offers a sign-in when signed out', () => {
    const { clock, tape, collar, text } = render();
    expect(clock).toBeNull();
    expect(tape).toBeNull();
    expect(collar).toBeNull();
    expect(text).toContain('Sign in to view');
  });

  it('renders the clock, the day view and the collar reference once signed in', () => {
    authUser.set('admin');
    const { clock, tape, collar } = render();
    expect(clock).not.toBeNull();
    expect(tape).not.toBeNull();
    expect(collar).not.toBeNull();
  });
});
