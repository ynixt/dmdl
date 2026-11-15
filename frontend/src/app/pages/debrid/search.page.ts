import { ChangeDetectorRef, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { Addons, Catalog, DebridApi, Metadata } from '../../services/debrid-api.service';
import { MediaCardComponent } from './media-card.component';
import { mediaRoute } from './media-route';
import { CatalogSearch, SearchBarComponent } from './search-bar.component';

@Component({
  selector: 'app-search',
  imports: [TranslatePipe, ButtonModule, SearchBarComponent, MediaCardComponent],
  template: `
    <div class="page">
      <h1>{{ 'KZ.SEARCH' | translate }}</h1>
      <app-search-bar
        [catalogs]="catalogs"
        [initialCatalog]="catalog"
        [initialQuery]="query"
        [busy]="busy()"
        (submitted)="search($event)"
      />
      @if (error()) {
        <p role="alert" class="error">{{ error() | translate }}</p>
      }
      @if (results().length || busy()) {
        <div class="row">
          <p-button
            [label]="'KZ.RELOAD_RESULTS' | translate"
            icon="pi pi-refresh"
            [disabled]="busy()"
            (onClick)="reload()"
          />
        </div>
      }
      <div
        class="cards"
        [attr.aria-busy]="busy()"
        [attr.role]="busy() ? 'status' : null"
        [attr.aria-label]="busy() ? ('KZ.LOADING' | translate) : null"
      >
        @if (busy()) {
          @for (index of skeletons; track index) {
            <app-media-card title="" [loading]="true" />
          }
        } @else {
          @for (item of results(); track $index) {
            <app-media-card
              [poster]="item.posterUrl"
              [title]="item.title"
              [year]="item.year"
              [commands]="mediaCommands(item)"
              [state]="{ mediaPreview: item }"
            />
          } @empty {
            @if (!busy() && !error()) {
              <p>{{ (query ? 'KZ.NO_SEARCH_RESULTS' : 'KZ.SEARCH_PROMPT') | translate }}</p>
            }
          }
        }
      </div>
    </div>
  `,
  styleUrls: ['./debrid.scss'],
})
export class SearchPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private requestRevision = 0;
  private addons: Addons = { search: [] };
  readonly busy = signal(false);
  readonly error = signal('');
  readonly results = signal<Metadata[]>([]);
  catalogs: Catalog[] = [];
  catalog: Catalog | null = null;
  query = '';
  skeletons = Array.from({ length: 12 }, (_, index) => index);

  constructor() {
    this.destroyRef.onDestroy(() => {
      this.requestRevision++;
    });
  }

  async ngOnInit(): Promise<void> {
    try {
      this.addons = await this.api.get<Addons>('/addons');
      if (this.destroyRef.destroyed) return;
      this.catalogs =
        this.addons.metadata?.manifest.catalogs.filter((item) => item.searchable) ?? [];
      this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
        void this.restoreFromUrl(params);
      });
    } catch (error) {
      this.showError(error);
    } finally {
      this.changeDetector.markForCheck();
    }
  }

  private async restoreFromUrl(params: ParamMap): Promise<void> {
    this.query = (params.get('q') ?? '').trim();
    this.catalog =
      this.catalogs.find((item) => `${item.type}:${item.id}` === params.get('catalog')) ??
      this.catalogs[0] ??
      null;
    await this.loadResults();
  }

  async search(value: CatalogSearch): Promise<void> {
    if (this.busy()) return;
    const navigated = await this.router.navigate(['/search'], {
      queryParams: { q: value.query.trim(), catalog: `${value.catalog.type}:${value.catalog.id}` },
      replaceUrl: true,
    });
    if (!navigated) await this.loadResults();
  }

  async reload(): Promise<void> {
    if (!this.busy()) await this.loadResults(true);
  }

  private async loadResults(forceRefresh = false): Promise<void> {
    const revision = ++this.requestRevision;
    this.error.set('');
    if (!this.query || !this.catalog) {
      this.results.set([]);
      this.busy.set(false);
      return;
    }
    if (this.results().length) this.skeletons = this.results().map((_, index) => index);
    this.busy.set(true);
    if (!forceRefresh) this.results.set([]);
    try {
      const results = await this.api.post<Metadata[]>(
        '/discovery/search' + (forceRefresh ? '?forceRefresh=true' : ''),
        {
          resourceType: this.catalog.type,
          catalogId: this.catalog.id,
          query: this.query,
        },
      );
      if (revision === this.requestRevision) this.results.set(results);
    } catch (error) {
      if (revision === this.requestRevision) this.showError(error);
    } finally {
      if (revision === this.requestRevision) {
        this.busy.set(false);
        this.changeDetector.markForCheck();
      }
    }
  }

  mediaCommands(item: Metadata): string[] | null {
    return mediaRoute(item, this.addons.metadata?.manifest);
  }

  private showError(error: unknown): void {
    this.error.set((error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED');
  }
}
