import { CommonModule, Location } from '@angular/common';
import {
  ChangeDetectorRef,
  DestroyRef,
  effect,
  ElementRef,
  viewChild,
  Component,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, NavigationEnd, NavigationSkipped, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import {
  Addons,
  Catalog,
  Category,
  Content,
  DebridApi,
  Metadata,
  Identity,
  Video,
} from '../../services/debrid-api.service';

export interface DiscoveryState {
  query: string;
  catalogId?: string;
  catalogType?: string;
  selection?: Identity;
  videoId?: string;
  category: Category;
}

@Component({
  selector: 'app-discovery',
  imports: [CommonModule, FormsModule, TranslatePipe, ButtonModule, InputTextModule, SelectModule],
  templateUrl: './discovery.page.html',
  changeDetection: ChangeDetectionStrategy.Default,
  styleUrls: ['./debrid.scss', './discovery.scss'],
})
export class DiscoveryPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private requestRevision = 0;
  private activeQuery = '';
  readonly busy = signal(false);
  readonly error = signal('');
  readonly results = signal<Metadata[]>([]);
  readonly details = signal<Metadata | null>(null);
  catalogs: Catalog[] = [];
  catalog: Catalog | null = null;
  query = '';
  video: Video | null = null;
  category: Category = 'MOVIE';
  readonly episodeCategories: Category[] = ['SERIES', 'ANIME'];
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private addons: Addons = { search: [] };
  private readonly detailsPanel = viewChild<ElementRef<HTMLElement>>('detailsPanel');
  private readonly resultCard = viewChild<ElementRef<HTMLButtonElement>>('resultCard');
  readonly resultRowHeight = signal<number | null>(null);

  constructor() {
    effect((onCleanup) => {
      const card = this.resultCard()?.nativeElement;
      if (!card) {
        this.resultRowHeight.set(null);
        return;
      }
      const measure = () => this.resultRowHeight.set(card.getBoundingClientRect().height);
      const observer = new ResizeObserver(measure);
      observer.observe(card);
      measure();
      onCleanup(() => observer.disconnect());
    });
  }
  async ngOnInit(): Promise<void> {
    try {
      this.addons = await this.api.get<Addons>('/addons');
      this.catalogs =
        this.addons.metadata?.manifest.catalogs.filter((catalog) => catalog.searchable) ?? [];
      this.router.events.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((event) => {
        if (!(event instanceof NavigationEnd || event instanceof NavigationSkipped)) return;
        const query = (this.route.snapshot.queryParamMap.get('q') ?? '').trim();
        if (query !== this.activeQuery || (!query && this.query)) void this.restoreFromUrl(query);
      });
      await this.restoreFromUrl((this.route.snapshot.queryParamMap.get('q') ?? '').trim());
    } catch (error) {
      this.showError(error);
    } finally {
      this.changeDetector.markForCheck();
    }
  }
  private async restoreFromUrl(query: string): Promise<void> {
    await this.run(async (revision) => {
      const saved = (this.location.getState() as { discovery?: DiscoveryState } | null)?.discovery;
      const state = query && saved?.query === query ? saved : undefined;
      this.activeQuery = query;
      this.query = query;
      this.results.set([]);
      this.details.set(null);
      this.video = null;
      this.category = 'MOVIE';
      this.catalog =
        this.catalogs.find(
          (item) => item.id === state?.catalogId && item.type === state?.catalogType,
        ) ??
        this.catalogs[0] ??
        null;
      if (!query || !this.catalog) return;
      await this.loadResults(query, this.catalog, revision);
      if (revision !== this.requestRevision || !state?.selection) return;
      await this.loadDetails(state.selection, revision);
      if (revision !== this.requestRevision) return;
      const details = this.details();
      this.video = details?.videos.find((item) => item.id === state.videoId) ?? null;
      if (
        details?.identity.resourceType !== 'movie' &&
        ['SERIES', 'ANIME'].includes(state.category)
      )
        this.category = state.category;
    });
  }
  private async loadResults(query: string, catalog: Catalog, revision: number): Promise<void> {
    const results = await this.api.post<Metadata[]>('/discovery/search', {
      resourceType: catalog.type,
      catalogId: catalog.id,
      query,
    });
    if (revision === this.requestRevision) this.results.set(results);
  }
  private async loadDetails(identity: Identity, revision: number): Promise<void> {
    const details = await this.api.post<Metadata>('/discovery/details', identity);
    if (revision !== this.requestRevision) return;
    this.details.set(details);
    this.category =
      details.identity.resourceType === 'movie'
        ? 'MOVIE'
        : details.identity.resourceType === 'anime'
          ? 'ANIME'
          : 'SERIES';
  }
  private navigationState(): DiscoveryState {
    return {
      query: this.activeQuery,
      catalogId: this.catalog?.id,
      catalogType: this.catalog?.type,
      selection: this.details()?.identity,
      videoId: this.video?.id,
      category: this.category,
    };
  }
  private async updateUrl(revision = this.requestRevision): Promise<void> {
    await this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: { q: this.activeQuery || null },
    });
    if (revision !== this.requestRevision) return;
    this.location.replaceState(this.router.url, '', {
      ...(this.location.getState() as object),
      discovery: this.navigationState(),
    });
  }
  async search(updateUrl = true): Promise<void> {
    const catalog = this.catalog;
    const query = this.query.trim();
    if (this.busy() || !catalog || !query) return;
    await this.run(async (revision) => {
      this.activeQuery = query;
      this.details.set(null);
      this.video = null;
      this.results.set([]);
      await this.loadResults(query, catalog, revision);
      if (revision === this.requestRevision && updateUrl) await this.updateUrl(revision);
    });
  }
  async open(item: Metadata, updateUrl = true): Promise<void> {
    if (this.busy()) return;
    await this.run(async (revision) => {
      this.video = null;
      await this.loadDetails(item.identity, revision);
      if (revision !== this.requestRevision || !updateUrl) return;
      await this.updateUrl(revision);
      if (revision !== this.requestRevision) return;
      this.changeDetector.detectChanges();
      if (window.matchMedia('(max-width: 900px)').matches)
        this.detailsPanel()?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
    });
  }
  content(): Content | null {
    const details = this.details();
    if (!details || (this.category !== 'MOVIE' && !this.video)) return null;
    return {
      identity: { ...details.identity, ...(this.video ? { videoId: this.video.id } : {}) },
      category: this.category,
      title: details.title,
      year: details.year,
      posterUrl: details.posterUrl,
      ...(this.video
        ? { season: this.video.season, episode: this.video.episode, episodeTitle: this.video.title }
        : {}),
    };
  }
  async findReleases(): Promise<void> {
    const content = this.content();
    if (!content) return;
    const source = this.addons.search.find((item) => item.enabled);
    if (!source) {
      this.error.set('KZ.RELEASE_SOURCE_UNAVAILABLE');
      return;
    }
    this.location.replaceState(this.router.url, '', {
      ...(this.location.getState() as object),
      discovery: this.navigationState(),
    });
    const resources = this.addons.metadata?.manifest.resources ?? [];
    const namespaces =
      this.category === 'MOVIE' ? ['tmdb', 'tvdb', 'imdb'] : ['tvdb', 'tmdb', 'imdb'];
    const mapped = namespaces.flatMap((namespace) => {
      const id = content.identity.mappedIds?.[namespace];
      return id ? [namespace === 'imdb' ? id : namespace + ':' + id] : [];
    });
    const id =
      mapped.find((id) =>
        resources.some(
          (resource) =>
            resource.name === 'meta' &&
            (!resource.types?.length || resource.types.includes(content.identity.resourceType)) &&
            resource.idPrefixes?.some((prefix) => id.startsWith(prefix)),
        ),
      ) ?? content.identity.contentId;
    await this.router.navigate(
      [
        '/discovery/releases',
        source.id,
        content.identity.resourceType,
        id,
        ...(content.category === 'MOVIE' ? [] : [content.season!, content.episode!]),
      ],
      {
        queryParams: {
          category:
            content.category.toLowerCase() !== content.identity.resourceType
              ? content.category
              : null,
        },
        state: { discovery: this.navigationState() },
      },
    );
  }
  async selectEpisode(video: Video): Promise<void> {
    this.video = video;
    await this.updateUrl();
  }
  async changeCategory(): Promise<void> {
    await this.updateUrl();
  }
  private async run(action: (revision: number) => Promise<void>): Promise<void> {
    const revision = ++this.requestRevision;
    this.busy.set(true);
    this.error.set('');
    try {
      await action(revision);
    } catch (error) {
      if (revision === this.requestRevision) this.showError(error);
    } finally {
      if (revision === this.requestRevision) {
        this.busy.set(false);
        this.changeDetector.markForCheck();
      }
    }
  }
  private showError(error: unknown): void {
    this.error.set((error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED');
  }
}
