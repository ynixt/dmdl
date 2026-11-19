import {
  afterNextRender,
  ChangeDetectorRef,
  Component,
  DestroyRef,
  inject,
  Injector,
  input,
  OnInit,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { Carousel, CarouselModule, CarouselPageEvent } from '@openng/optimus-ui/carousel';
import { SelectModule } from '@openng/optimus-ui/select';
import {
  Addon,
  Catalog,
  CatalogExtra,
  DebridApi,
  Metadata,
} from '../../services/debrid-api.service';
import { MediaCardComponent } from './media-card.component';
import { mediaRoute } from './media-route';

export interface CatalogRowSnapshot {
  items: Metadata[];
  page: number;
  exhausted: boolean;
  fresh: boolean;
  extras?: Record<string, string>;
  selectionVersion?: 1;
}

interface CatalogFilter extends CatalogExtra {
  choices: { value: string }[];
}

@Component({
  selector: 'app-catalog-row',
  imports: [
    TranslatePipe,
    FormsModule,
    ButtonModule,
    CarouselModule,
    SelectModule,
    MediaCardComponent,
  ],
  template: `
    @let rowTitle = catalog().name + ' | ' + (typeLabel() | translate);
    <section [attr.aria-label]="rowTitle" [attr.aria-busy]="busy()">
      <header>
        <h2>{{ rowTitle }}</h2>
        <div class="controls">
          @for (extra of filters; track extra.name) {
            @if ((extra.options?.length ?? 0) > 1) {
              <p-select
                [options]="extra.choices"
                optionLabel="value"
                optionValue="value"
                [(ngModel)]="selectedExtras[extra.name]"
                [ariaLabel]="
                  extra.name === 'genre' ? ('KZ.CATALOG_OPTION' | translate) : extra.name
                "
                [placeholder]="'KZ.ALL_CATALOG_OPTIONS' | translate"
                [showClear]="!extra.isRequired && selectedExtras[extra.name] !== ''"
                [disabled]="busy()"
                (onChange)="reload(false)"
              >
                <ng-template #item let-choice>
                  {{ choice.value === '' ? ('KZ.ALL_CATALOG_OPTIONS' | translate) : choice.value }}
                </ng-template>
                <ng-template #selectedItem let-choice>
                  {{ choice.value === '' ? ('KZ.ALL_CATALOG_OPTIONS' | translate) : choice.value }}
                </ng-template>
              </p-select>
            }
          }
          <p-button
            [label]="'KZ.RELOAD_CATALOG' | translate"
            icon="pi pi-refresh"
            [text]="true"
            [disabled]="busy()"
            (onClick)="reload()"
          />
        </div>
      </header>
      @if (!busy() && !items().length && filters.length) {
        <p [attr.role]="failed() ? 'alert' : 'status'">
          {{ (failed() ? 'KZ.REQUEST_FAILED' : 'KZ.NO_CATALOG_ITEMS') | translate }}
        </p>
      }
      @if (items().length || busy()) {
        <div
          [attr.role]="!items().length ? 'status' : null"
          [attr.aria-label]="!items().length ? ('KZ.LOADING' | translate) : null"
        >
          <p-carousel
            [value]="items().length ? items() : skeletons"
            [attr.inert]="!items().length ? '' : null"
            [page]="page"
            [numVisible]="6"
            [numScroll]="6"
            [responsiveOptions]="responsiveOptions"
            [showNavigators]="true"
            [showIndicators]="false"
            [circular]="false"
            (onPage)="onPage($event)"
          >
            <ng-template #item let-item>
              @if (item) {
                <app-media-card
                  [poster]="item.posterUrl"
                  [title]="item.title"
                  [year]="item.year"
                  [commands]="route(item)"
                  [state]="{ mediaPreview: item }"
                  (beforeNavigate)="beforeNavigate.emit()"
                />
              } @else {
                <app-media-card title="" [loading]="true" />
              }
            </ng-template>
          </p-carousel>
        </div>
      }
    </section>
  `,
  styles: [
    `
      :host {
        --catalog-card-margin: 0.35rem;
        display: block;
        min-width: 0;
      }
      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 1rem;
        flex-wrap: wrap;
        margin-bottom: 0.75rem;
        padding-inline: calc(
          var(--p-button-icon-only-width, 2.5rem) + var(--p-carousel-content-gap, 0.25rem) +
            var(--catalog-card-margin)
        );
      }
      .controls {
        display: flex;
        align-items: center;
        justify-content: flex-end;
        flex-wrap: wrap;
        gap: 0.75rem;
        min-width: 0;
        max-width: 100%;
      }
      p-select {
        min-width: 10rem;
        max-width: 100%;
      }
      h2 {
        margin: 0;
        font-size: 1.15rem;
        font-weight: 650;
      }
      app-media-card {
        height: 100%;
        margin: 0 var(--catalog-card-margin);
      }
    `,
  ],
})
export class CatalogRowComponent implements OnInit {
  readonly catalog = input.required<Catalog>();
  readonly manifest = input<Addon['manifest']>();
  readonly restored = input<CatalogRowSnapshot>();
  readonly changed = output<CatalogRowSnapshot>();
  readonly unavailable = output<void>();
  readonly beforeNavigate = output<void>();
  readonly items = signal<Metadata[]>([]);
  readonly busy = signal(false);
  readonly failed = signal(false);
  readonly carousel = viewChild(Carousel);
  readonly skeletons: null[] = Array(6).fill(null);
  readonly responsiveOptions = [
    { breakpoint: '1199px', numVisible: 5, numScroll: 5 },
    { breakpoint: '991px', numVisible: 4, numScroll: 4 },
    { breakpoint: '767px', numVisible: 3, numScroll: 3 },
    { breakpoint: '575px', numVisible: 2, numScroll: 2 },
    { breakpoint: '359px', numVisible: 1, numScroll: 1 },
  ];
  page = 0;
  exhausted = false;
  fresh = false;
  filters: CatalogFilter[] = [];
  selectedExtras: Record<string, string> = {};
  private readonly api = inject(DebridApi);
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly injector = inject(Injector);

  async ngOnInit(): Promise<void> {
    const restored = this.restored();
    this.filters =
      this.catalog()
        .extra?.filter((extra) => extra.options?.length && !['search', 'skip'].includes(extra.name))
        .map((extra) => ({
          ...extra,
          choices: (extra.isRequired
            ? (extra.options ?? [])
            : [...new Set(['', ...(extra.options ?? [])])]
          ).map((value) => ({ value })),
        })) ?? [];
    for (const extra of this.filters) {
      const saved =
        extra.isRequired || restored?.selectionVersion === 1
          ? restored?.extras?.[extra.name]
          : undefined;
      const options = extra.options ?? [];
      const requiredDefault =
        extra.default != null && options.includes(extra.default) ? extra.default : options[0];
      const initial = extra.isRequired ? requiredDefault : '';
      this.selectedExtras[extra.name] =
        saved !== undefined && extra.choices.some((choice) => choice.value === saved)
          ? saved
          : initial;
    }
    const compatible = this.filters.every(
      (extra) => (restored?.extras?.[extra.name] ?? '') === this.selectedExtras[extra.name],
    );
    if (restored && compatible) {
      this.items.set(restored.items);
      this.page = restored.page;
      this.exhausted = restored.exhausted;
      this.fresh = restored.fresh;
      // Optimus UI computes the initial offset after rendering its active-item attributes.
      afterNextRender(() => this.carousel()?.cd.detectChanges(), { injector: this.injector });
      if (!restored.items.length && !restored.exhausted) await this.load();
    } else {
      await this.load();
    }
  }

  snapshot(): CatalogRowSnapshot {
    return {
      items: this.items(),
      page: this.page,
      exhausted: this.exhausted,
      fresh: this.fresh,
      ...(this.filters.length
        ? { extras: { ...this.selectedExtras }, selectionVersion: 1 as const }
        : {}),
    };
  }

  onPage(event: CarouselPageEvent): void {
    if (!this.items().length) return;
    this.page = event.page ?? 0;
    this.changed.emit(this.snapshot());
    this.loadNextAtEnd();
  }

  private loadNextAtEnd(): void {
    const carousel = this.carousel();
    if (
      carousel &&
      this.page >= carousel.totalDots() - 1 &&
      this.catalog().pageable &&
      !this.exhausted
    ) {
      void this.load();
    }
  }

  async reload(forceRefresh = true): Promise<void> {
    if (this.busy()) return;
    for (const extra of this.filters) this.selectedExtras[extra.name] ??= '';
    this.items.set([]);
    this.page = 0;
    this.exhausted = false;
    this.fresh = forceRefresh;
    this.changed.emit(this.snapshot());
    await this.load();
  }

  typeLabel(): string {
    return (
      ({ movie: 'KZ.MOVIE', series: 'KZ.SERIES', anime: 'KZ.ANIME' } as Record<string, string>)[
        this.catalog().type
      ] ?? this.catalog().type
    );
  }

  route(item: Metadata): string[] | null {
    return mediaRoute(item, this.manifest());
  }

  private async load(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.failed.set(false);
    const skip = this.items().length;
    const catalog = this.catalog();
    const query = new URLSearchParams({ skip: String(skip) });
    for (const extra of this.filters) query.set(extra.name, this.selectedExtras[extra.name] ?? '');
    if (this.fresh) query.set('forceRefresh', 'true');
    try {
      const items = await this.api.get<Metadata[]>(
        `/discovery/catalogs/${encodeURIComponent(catalog.type)}/${encodeURIComponent(catalog.id)}?${query}`,
      );
      if (this.destroyRef.destroyed) return;
      this.items.update((loaded) => [...loaded, ...items]);
      this.exhausted = !items.length || !catalog.pageable;
      if (!this.items().length && !this.filters.length) this.unavailable.emit();
      this.changed.emit(this.snapshot());
      afterNextRender(
        () => {
          this.carousel()?.cd.detectChanges();
          this.loadNextAtEnd();
        },
        { injector: this.injector },
      );
    } catch {
      if (this.destroyRef.destroyed) return;
      this.failed.set(true);
      if (skip === 0 && !this.filters.length) this.unavailable.emit();
    } finally {
      if (!this.destroyRef.destroyed) {
        this.busy.set(false);
        this.changeDetector.markForCheck();
      }
    }
  }
}
