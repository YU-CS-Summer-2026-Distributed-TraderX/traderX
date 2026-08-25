import { NgModule } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ClusterComponent } from './cluster.component';
import { EodComponent } from './eod.component';
import { OtcComponent } from './otc.component';
import { BandsComponent } from './bands.component';
import { TickStoreComponent } from './tickstore.component';
import { PlatformMetricsComponent } from './metrics.component';

/**
 * YU17: the read-model views this state added to the ORIGINAL app.
 *
 * Kept in one module so the additions are legible as a group — a mentor reading this tree can see
 * exactly what was added to the upstream app and what was left alone. Nothing here writes: every
 * view is a window onto state the cluster already agreed, which is why none of them needs the
 * operator credential the write paths do.
 */
const VIEWS = [
    ClusterComponent, EodComponent, OtcComponent,
    BandsComponent, TickStoreComponent, PlatformMetricsComponent
];

@NgModule({
    declarations: VIEWS,
    imports: [CommonModule, FormsModule],
    exports: VIEWS
})
export class PlatformModule {}
