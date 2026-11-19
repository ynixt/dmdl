import { Component, effect, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { InputTextModule } from '@openng/optimus-ui/inputtext';
import { SelectModule } from '@openng/optimus-ui/select';
import { Catalog } from '../../services/debrid-api.service';

export interface CatalogSearch {
  catalog: Catalog;
  query: string;
}

@Component({
  selector: 'app-search-bar',
  imports: [FormsModule, TranslatePipe, ButtonModule, InputTextModule, SelectModule],
  template: `
    <form class="panel form-row" (ngSubmit)="submit()">
      <div class="field">
        <label for="catalog">{{ 'KZ.CATALOG' | translate }}</label>
        <p-select
          inputId="catalog"
          [options]="searchableCatalogs"
          optionLabel="name"
          [(ngModel)]="catalog"
          name="catalog"
        />
      </div>
      <div class="field search-query">
        <label for="query">{{ 'KZ.TITLE_SEARCH' | translate }}</label>
        <input pInputText id="query" [(ngModel)]="query" name="query" />
      </div>
      <p-button
        type="submit"
        [label]="'KZ.SEARCH' | translate"
        icon="pi pi-search"
        [loading]="busy()"
        [disabled]="busy() || !catalog || !query.trim()"
      />
    </form>
  `,
  styleUrls: ['./debrid.scss'],
})
export class SearchBarComponent {
  readonly catalogs = input<Catalog[]>([]);
  readonly initialCatalog = input<Catalog | null>(null);
  readonly initialQuery = input('');
  readonly busy = input(false);
  readonly submitted = output<CatalogSearch>();
  searchableCatalogs: Catalog[] = [];
  catalog: Catalog | null = null;
  query = '';

  constructor() {
    effect(() => {
      this.searchableCatalogs = this.catalogs().filter((item) => item.searchable);
      const initial = this.initialCatalog();
      this.catalog =
        this.searchableCatalogs.find(
          (item) => item.id === initial?.id && item.type === initial?.type,
        ) ??
        this.searchableCatalogs[0] ??
        null;
    });
    effect(() => {
      this.query = this.initialQuery();
    });
  }

  submit(): void {
    const query = this.query.trim();
    if (this.busy() || !this.catalog || !query) return;
    this.submitted.emit({ catalog: this.catalog, query });
  }
}
