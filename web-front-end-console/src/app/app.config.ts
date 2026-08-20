import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TradingPage, SystemPage, EodPage, AdminPage, KdbPage, AccountsPage } from './pages';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter([
      { path: '', component: TradingPage },
      { path: 'system', component: SystemPage },
      { path: 'eod', component: EodPage },
      { path: 'admin', component: AdminPage },
      { path: 'accounts', component: AccountsPage },
      { path: 'kdb', component: KdbPage },
    ]),
  ],
};
