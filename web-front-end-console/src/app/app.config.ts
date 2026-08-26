import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TradingPage, SystemPage, EodPage, AdminPage, KdbPage, AccountsPage, FixPage,
  GrafanaPage, LegacyPage, ReplayPage } from './pages';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter([
      { path: '', component: TradingPage },
      { path: 'system', component: SystemPage },
      { path: 'eod', component: EodPage },
      { path: 'replay', component: ReplayPage },
      { path: 'admin', component: AdminPage },
      { path: 'accounts', component: AccountsPage },
      { path: 'fix', component: FixPage },
      { path: 'kdb', component: KdbPage },
      { path: 'grafana', component: GrafanaPage },
      { path: 'legacy', component: LegacyPage },
    ]),
  ],
};
