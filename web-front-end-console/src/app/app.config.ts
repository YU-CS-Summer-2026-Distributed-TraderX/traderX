import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter, Routes } from '@angular/router';
import { TradingPage, SystemPage, EodPage, AdminPage, KdbPage, AccountsPage, FixPage,
  GrafanaPage, LegacyPage, ReplayPage, SandboxPage, CorpusPage } from './pages';
import { RiskPage } from './risk-page';

export const routes: Routes = [
  { path: '', component: TradingPage },
  { path: 'system', component: SystemPage },
  { path: 'eod', component: EodPage },
  { path: 'risk', component: RiskPage },
  { path: 'replay', component: ReplayPage },
  { path: 'sandbox', component: SandboxPage },
  { path: 'corpus', component: CorpusPage },
  { path: 'admin', component: AdminPage },
  { path: 'accounts', component: AccountsPage },
  { path: 'fix', component: FixPage },
  { path: 'kdb', component: KdbPage },
  { path: 'grafana', component: GrafanaPage },
  { path: 'legacy', component: LegacyPage },
];

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
  ],
};
