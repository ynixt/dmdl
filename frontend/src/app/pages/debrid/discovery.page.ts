import { Location } from '@angular/common';
import { ChangeDetectorRef, Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Addons, Catalog, DebridApi, Metadata } from '../../services/debrid-api.service';
import { CatalogRowComponent, CatalogRowSnapshot } from './catalog-row.component';
import { mediaRoute, PREVIEW_STATE } from './media-route';
import { CatalogSearch, SearchBarComponent } from './search-bar.component';

interface DiscoveryRows {
  sourceId: string;
  sourceRevision: number;
  rows: Record<string, CatalogRowSnapshot>;
  hidden: string[];
}

@Component({
  selector: 'app-discovery',
  imports: [TranslatePipe, SearchBarComponent, CatalogRowComponent],
  templateUrl: './discovery.page.html',
  styleUrls: ['./debrid.scss', './discovery.scss'],
})
export class DiscoveryPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  private readonly changeDetector = inject(ChangeDetectorRef);
  readonly error = signal('');
  readonly hidden = signal<string[]>([]);
  addons: Addons = { search: [] };
  catalogs: Catalog[] = [];
  rows: Catalog[] = [];
  snapshots: Record<string, CatalogRowSnapshot> = {};
  restored: Record<string, CatalogRowSnapshot> = {};

  async ngOnInit(): Promise<void> {
    try {
      this.addons = await this.api.get<Addons>('/addons');
      if (this.destroyRef.destroyed) return;
      this.catalogs = this.addons.metadata?.manifest.catalogs ?? [];
      this.rows = this.catalogs.filter((catalog) => catalog.browsable).slice(0, 10);
      const saved = (this.location.getState() as { discoveryRows?: DiscoveryRows } | null)
        ?.discoveryRows;
      if (
        saved &&
        saved.sourceId === this.addons.metadata?.id &&
        saved.sourceRevision === this.addons.metadata?.revision
      ) {
        this.restored = saved.rows;
        this.snapshots = { ...saved.rows };
        this.hidden.set(
          saved.hidden.filter(
            (key) =>
              !this.catalogs.some(
                (catalog) =>
                  this.key(catalog) === key &&
                  catalog.extra?.some((extra) => extra.options?.length),
              ),
          ),
        );
      }
    } catch (error) {
      this.error.set(
        (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
      );
    } finally {
      this.changeDetector.markForCheck();
    }
  }

  key(catalog: Catalog): string {
    return `${catalog.type}:${catalog.id}`;
  }

  async search(value: CatalogSearch): Promise<void> {
    await this.router.navigate(['/search'], {
      queryParams: { q: value.query.trim(), catalog: this.key(value.catalog) },
    });
  }

  update(catalog: Catalog, snapshot: CatalogRowSnapshot): void {
    this.snapshots[this.key(catalog)] = snapshot;
    this.save();
  }

  hide(catalog: Catalog): void {
    this.hidden.update((hidden) => [...hidden, this.key(catalog)]);
    this.save();
  }

  private save(): void {
    const source = this.addons.metadata;
    if (!source) return;
    const discoveryRows: DiscoveryRows = {
      sourceId: source.id,
      sourceRevision: source.revision,
      rows: this.snapshots,
      hidden: this.hidden(),
    };
    this.location.replaceState(this.router.url, '', {
      ...(this.location.getState() as object),
      discoveryRows,
    });
  }

  async open(item: Metadata): Promise<void> {
    const commands = mediaRoute(item, this.addons.metadata?.manifest);
    if (!commands) return;
    this.save();
    await this.router.navigate(commands, { state: { [PREVIEW_STATE]: item } });
  }
}
