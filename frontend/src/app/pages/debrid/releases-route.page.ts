import { Component } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { ReleasesPage } from './releases.page';

@Component({
  selector: 'app-releases-route',
  imports: [ReleasesPage, TranslatePipe],
  template: `
    @defer (on immediate) {
      <app-releases />
    } @placeholder {
      <main class="page">
        <h1>{{ 'KZ.RELEASES' | translate }}</h1>
        <div class="release-loading" role="status" aria-live="polite" aria-busy="true">
          <i class="pi pi-spinner pi-spin route-spinner" aria-hidden="true"></i>
          <span>{{ 'KZ.LOADING' | translate }}</span>
        </div>
      </main>
    }
  `,
  styleUrls: ['./debrid.scss', './releases.scss'],
  styles: '.route-spinner { font-size: 3rem; }',
})
export class ReleasesRoutePage {}
