import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { TradingPage, SystemPage, EodPage } from './pages';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter([
      { path: '', component: TradingPage },
      { path: 'system', component: SystemPage },
      { path: 'eod', component: EodPage },
    ]),
  ],
};
