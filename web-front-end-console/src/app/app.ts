import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';
import { SessionDriver } from './demo-session';
import { SignIn } from './sign-in';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive, SignIn],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  private api = inject(Api);
  /** The live session runs in a service, so its state belongs in the shell, not on one page. */
  readonly session = inject(SessionDriver);
  private timer: ReturnType<typeof setInterval> | undefined;
  /** null = checking; otherwise the HTTP status of the gateway's /ready through the edge proxy
   *  (0 = nothing answered). It used to be `status > 0` on /health, so a 502/504 from a proxy with a
   *  dead gateway read "rig connected" (RI-07). 200 means the gateway holds a cluster session --
   *  not that the whole rig works; scripts/ri07/rig-ready.sh is the full-rig check. */
  readonly rigStatus = signal<number | null>(null);
  /** The project's own github.io site — same URL the docusaurus build publishes to. */
  readonly siteUrl = 'https://YU-CS-Summer-2026-Distributed-TraderX.github.io/traderX/';

  ngOnInit(): void {
    this.api.init();
    // Ask once at start-up: the cookie outlives a reload, so the shell must not assume signed-out.
    this.api.checkAuth();
    this.check();
    this.timer = setInterval(() => this.check(), 5000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  private async check(): Promise<void> {
    const r = await this.api.load<string>('/order-matcher/ready');
    this.rigStatus.set(r.status);
  }
}
