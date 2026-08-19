import { Component, OnInit, inject } from '@angular/core';
import { Api } from './api';
import { ClusterPanel } from './cluster-panel';
import { TicketPanel } from './ticket-panel';
import { BlotterPanel } from './blotter-panel';
import { MetricsPanel } from './metrics-panel';
import { ActivityPanel } from './activity-panel';
import { EodPanel } from './eod-panel';

@Component({
  selector: 'app-root',
  imports: [ClusterPanel, TicketPanel, BlotterPanel, MetricsPanel, ActivityPanel, EodPanel],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App implements OnInit {
  private api = inject(Api);
  ngOnInit(): void { this.api.init(); }
}
