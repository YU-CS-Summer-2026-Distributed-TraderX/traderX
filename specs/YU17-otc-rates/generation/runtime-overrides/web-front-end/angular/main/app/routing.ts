import { Routes } from '@angular/router';
import { AboutComponent } from './about/about.component';
import { StatusComponent } from './status/status.component';
import { AccountComponent } from './accounts/account.component';
import { PageNotFoundComponent } from './page-not-found.component';
import { TradeComponent } from './trade/trade.component';
import { OrderAdminComponent } from './admin/order-admin.component';
import { ClusterComponent } from './platform/cluster.component';
import { EodComponent } from './platform/eod.component';
import { OtcComponent } from './platform/otc.component';
import { BandsComponent } from './platform/bands.component';
import { TickStoreComponent } from './platform/tickstore.component';

/**
 * YU17: adds the read-model routes. The upstream five are untouched and keep their paths, so every
 * existing link and bookmark still resolves — this layer adds windows, it does not move anything.
 */
export const routes: Routes = [
    { path: 'about', component: AboutComponent },
    { path: 'status', component: StatusComponent },
    { path: 'trade', component: TradeComponent },
    { path: 'account', component: AccountComponent },
    { path: 'admin', component: OrderAdminComponent },
    { path: 'cluster', component: ClusterComponent },
    { path: 'eod', component: EodComponent },
    { path: 'otc', component: OtcComponent },
    { path: 'bands', component: BandsComponent },
    { path: 'tick-capture', component: TickStoreComponent },
    { path: '', redirectTo: '/trade', pathMatch: 'full' },
    { path: '**', component: PageNotFoundComponent }
];
