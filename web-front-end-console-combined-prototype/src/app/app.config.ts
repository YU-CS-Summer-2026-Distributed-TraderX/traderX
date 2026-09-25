import { ApplicationConfig, inject, provideBrowserGlobalErrorListeners, provideZoneChangeDetection } from '@angular/core';
import { CanActivateFn, Router, Routes, provideRouter, withComponentInputBinding } from '@angular/router';
import { LoginPage } from './app';
import { Session, canUse } from './session';
import { MarketsPage, OrdersPage, OverviewPage, PositionsPage, RiskPage } from './trader';
import { AccountsAdminPage, ControlsPage, HealthPage, MonitoringPage, OperationsPage } from './admin';
import { ToolPage } from './tools';

// UI guards only. They decide what to render; the server must refuse the same requests (plan.md).
const signedIn: CanActivateFn = (_r, state) =>
  inject(Session).check() || inject(Router).createUrlTree(['/login'], { queryParams: { next: state.url } });
const admin: CanActivateFn = (_r, state) => {
  const s = inject(Session);
  if (!s.check()) return inject(Router).createUrlTree(['/login'], { queryParams: { next: state.url } });
  return canUse(s.user(), 'admin') || inject(Router).createUrlTree(['/desk/overview']);
};
const lastTab = (ws: 'trader' | 'admin'): CanActivateFn => () => {
  const p = inject(Session).prefs();
  return inject(Router).createUrlTree(ws === 'admin' ? ['/admin', p.lastAdminTab] : ['/desk', p.lastTraderTab]);
};

export const routes: Routes = [
  { path: 'login', component: LoginPage, title: 'Sign in · TraderX Desk prototype' },
  { path: 'desk', canActivate: [signedIn], children: [
    { path: '', pathMatch: 'full', canActivate: [lastTab('trader')], children: [] },
    { path: 'overview', component: OverviewPage, title: 'Overview · TraderX Desk' },
    { path: 'markets', component: MarketsPage, title: 'Markets · TraderX Desk' },
    { path: 'orders', component: OrdersPage, title: 'Orders · TraderX Desk' },
    { path: 'positions', component: PositionsPage, title: 'Positions · TraderX Desk' },
    { path: 'risk', component: RiskPage, title: 'Risk · TraderX Desk' },
    { path: 'tools/:id', component: ToolPage, title: 'Tools · TraderX Desk' },
  ] },
  { path: 'admin', canActivate: [admin], children: [
    { path: '', pathMatch: 'full', canActivate: [lastTab('admin')], children: [] },
    { path: 'operations', component: OperationsPage, title: 'End of day · TraderX Admin' },
    { path: 'health', component: HealthPage, title: 'Health · TraderX Admin' },
    { path: 'accounts', component: AccountsAdminPage, title: 'Accounts · TraderX Admin' },
    { path: 'controls', component: ControlsPage, title: 'Controls · TraderX Admin' },
    { path: 'observability', component: MonitoringPage, title: 'Monitoring · TraderX Admin' },
    { path: 'tools/:id', component: ToolPage, title: 'Tools · TraderX Admin' },
  ] },
  { path: '**', redirectTo: 'desk' },
];

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes, withComponentInputBinding()),
  ],
};
