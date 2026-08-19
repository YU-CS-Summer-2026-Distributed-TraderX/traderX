import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { Api } from './api';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit, OnDestroy {
  private api = inject(Api);
  private timer: ReturnType<typeof setInterval> | undefined;
  /** null = checking, true/false = edge proxy reachable. Panels hold last values on failure;
   *  this chip is the single honest signal that the backend itself is gone. */
  readonly rigUp = signal<boolean | null>(null);

  ngOnInit(): void {
    this.api.init();
    this.check();
    this.timer = setInterval(() => this.check(), 5000);
  }
  ngOnDestroy(): void { clearInterval(this.timer); }

  private async check(): Promise<void> {
    const r = await this.api.load<string>('/order-matcher/health');
    this.rigUp.set(r.status > 0);
  }
}
