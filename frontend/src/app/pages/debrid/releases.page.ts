import { CommonModule, Location } from '@angular/common';
import {
  ChangeDetectorRef,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  OnInit,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { PaginatorModule } from 'primeng/paginator';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { SelectModule } from 'primeng/select';
import { combineLatest, debounceTime, skip } from 'rxjs';
import {
  Addon,
  Addons,
  Category,
  Content,
  DebridApi,
  Organization,
  Release,
  ReleasePage,
  Settings,
  StorageDestination,
} from '../../services/debrid-api.service';
import type { DiscoveryState } from './discovery.page';
import { StreamBadgesComponent } from './stream-badges.component';
import { FlagTextComponent } from './flag-text.component';

@Component({
  selector: 'app-releases',
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    ButtonModule,
    PaginatorModule,
    ProgressSpinnerModule,
    StreamBadgesComponent,
    FlagTextComponent,
    SelectModule,
    RouterLink,
  ],
  templateUrl: './releases.page.html',
  styleUrls: ['./debrid.scss', './releases.scss'],
})
export class ReleasesPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);
  private readonly destroyRef = inject(DestroyRef);
  private operation = 0;
  private contentKey = '';
  readonly busy = signal(true);
  readonly error = signal('');
  readonly downloadBarHeight = signal(0);
  private readonly downloadBar = viewChild<ElementRef<HTMLElement>>('downloadBar');
  readonly result = signal<ReleasePage | null>(null);
  readonly groups = computed(() => (this.result() ? [this.result()!.group] : []));
  readonly discoveryState = (this.location.getState() as { discovery?: DiscoveryState } | null)
    ?.discovery;
  readonly backParams = { q: this.discoveryState?.query || null };
  sources: Addon[] = [];
  addonId = '';
  category: Category = 'MOVIE';
  release: Release | null = null;
  selectedContent: Content | null = null;
  organization: Organization | null = null;
  destinationId: string | null = null;
  private destinationCategory: Category | null = null;

  constructor() {
    effect((onCleanup) => {
      const bar = this.downloadBar()?.nativeElement;
      if (!bar) {
        this.downloadBarHeight.set(0);
        return;
      }
      const measure = () =>
        this.downloadBarHeight.set(Math.ceil(bar.getBoundingClientRect().height));
      const observer = new ResizeObserver(measure);
      observer.observe(bar, { box: 'border-box' });
      measure();
      onCleanup(() => observer.disconnect());
    });
  }

  content(): Content | null {
    return this.selectedContent;
  }
  destinations(): StorageDestination[] {
    return this.organization?.categories[this.category].destinations ?? [];
  }
  selectDefaultDestination(): void {
    if (this.destinationCategory !== this.category) {
      this.destinationId =
        this.organization?.categories[this.category].defaultDestinationId ?? null;
      this.destinationCategory = this.category;
    }
  }
  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const [addons, settings] = await Promise.all([
        this.api.get<Addons>('/addons'),
        this.api.get<Settings>('/settings'),
      ]);
      this.sources = addons.search.filter((item) => item.enabled);
      this.organization = settings.organization;
    });
    if (!this.organization) return;
    combineLatest([this.route.paramMap, this.route.queryParamMap])
      .pipe(skip(1), debounceTime(0), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.loadRoute());
    await this.loadRoute();
  }
  private async loadRoute(): Promise<void> {
    window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
    await this.run(async (operation) => {
      this.result.set(null);
      this.release = null;
      const params = this.route.snapshot.paramMap;
      const query = this.route.snapshot.queryParamMap;
      const addonId = params.get('addonId') ?? '';
      const sourceChanged = !!this.addonId && this.addonId !== addonId;
      this.addonId = addonId;
      if (!this.sources.some((source) => source.id === this.addonId)) {
        this.selectedContent = null;
        throw { error: { error: 'KZ.RELEASE_SOURCE_UNAVAILABLE' } };
      }
      const resourceType = params.get('type');
      const contentId = params.get('id');
      const page = Number(query.get('page') ?? 1);
      if (!resourceType || !contentId || !Number.isSafeInteger(page) || page < 1) {
        this.selectedContent = null;
        throw { error: { error: 'KZ.RELEASE_CONTEXT_INVALID' } };
      }
      const request = new URLSearchParams({ resourceType, contentId });
      for (const field of ['season', 'episode']) {
        const value = params.get(field);
        if (value !== null) request.set(field, value);
      }
      if (query.get('category')) request.set('category', query.get('category')!);
      const key = request.toString();
      if (key !== this.contentKey || !this.selectedContent) {
        this.selectedContent = null;
        const content = await this.api.get<Content>('/discovery/content?' + key);
        if (operation !== this.operation) return;
        this.selectedContent = content;
        this.contentKey = key;
      }
      this.category = this.selectedContent!.category;
      this.selectDefaultDestination();
      await this.loadReleases(sourceChanged, page, operation);
    });
  }
  async findReleases(): Promise<void> {
    if (this.busy() || !this.selectedContent) return;
    await this.run((operation) => this.loadReleases(true, this.result()?.page ?? 1, operation));
  }
  private async loadReleases(
    forceRefresh: boolean,
    page: number,
    operation: number,
  ): Promise<void> {
    this.release = null;
    this.result.set(null);
    const query = new URLSearchParams({ addonId: this.addonId, page: String(page) });
    if (forceRefresh) query.set('forceRefresh', 'true');
    const result = await this.api.post<ReleasePage>(
      '/discovery/releases/page?' + query,
      this.selectedContent,
    );
    if (operation !== this.operation) return;
    this.result.set(result);
    if (result.page !== page) await this.navigatePage(result.page, true);
  }
  async changePage(page: number): Promise<void> {
    if (this.busy()) return;
    await this.navigatePage(page, false);
  }
  private navigatePage(page: number, replaceUrl: boolean): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl,
      queryParams: {
        category: this.route.snapshot.queryParamMap.get('category'),
        page: page === 1 ? null : page,
      },
      state: { discovery: this.discoveryState },
    });
  }
  async selectSource(id: string): Promise<void> {
    if (this.busy()) return;
    const params = this.route.snapshot.paramMap;
    await this.router.navigate(
      [
        '/discovery/releases',
        id,
        params.get('type'),
        params.get('id'),
        ...(params.has('season') ? [params.get('season'), params.get('episode')] : []),
      ],
      {
        queryParams: { category: this.route.snapshot.queryParamMap.get('category') },
        state: { discovery: this.discoveryState },
      },
    );
  }
  async submit(): Promise<void> {
    if (this.busy()) return;
    const content = this.content();
    if (!content || !this.release || !this.destinationId) return;
    await this.run(async () => {
      await this.api.post('/downloads', {
        content,
        release: this.release,
        destinationId: this.destinationId,
      });
      await this.router.navigate(['/downloads']);
    });
  }
  selectRelease(release: Release): void {
    if (this.busy()) return;
    this.release = this.release === release ? null : release;
  }
  async downloadRelease(release: Release): Promise<void> {
    if (this.busy()) return;
    if (this.release !== release) this.selectRelease(release);
    await this.submit();
  }
  private async run(action: (operation: number) => Promise<void>): Promise<void> {
    const operation = ++this.operation;
    this.busy.set(true);
    this.error.set('');
    try {
      await action(operation);
    } catch (error) {
      if (operation === this.operation)
        this.error.set(
          (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
        );
    } finally {
      if (operation === this.operation) {
        this.busy.set(false);
        this.changeDetector.markForCheck();
      }
    }
  }
}
