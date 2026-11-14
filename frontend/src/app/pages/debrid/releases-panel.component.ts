import { CommonModule } from '@angular/common';
import {
  ChangeDetectorRef,
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  effect,
  ElementRef,
  inject,
  OnInit,
  input,
  signal,
  viewChild,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { PaginatorModule } from 'primeng/paginator';
import { ProgressSpinnerModule } from 'primeng/progressspinner';
import { SelectModule } from 'primeng/select';
import { TabsModule } from 'primeng/tabs';
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
} from '../../services/debrid-api.service';
import { StreamBadgesComponent } from './stream-badges.component';
import { FlagTextComponent } from './flag-text.component';

@Component({
  selector: 'app-releases-panel',
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
    TabsModule,
  ],
  templateUrl: './releases-panel.component.html',
  styleUrls: ['./debrid.scss', './releases-panel.component.scss'],
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ReleasesPanelComponent implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);
  private operation = 0;
  readonly content = input.required<Content>();
  private initialized = false;
  readonly busy = signal(true);
  readonly error = signal('');
  readonly downloadBarHeight = signal(0);
  private readonly downloadBar = viewChild<ElementRef<HTMLElement>>('downloadBar');
  readonly result = signal<ReleasePage | null>(null);
  readonly groups = computed(() => (this.result() ? [this.result()!.group] : []));
  sources: Addon[] = [];
  addonId = '';
  release: Release | null = null;
  organization: Organization | null = null;
  destination: { id: string; name: string; path: string; category: Category } | null = null;

  constructor() {
    effect(() => {
      this.content();
      if (this.initialized) {
        this.selectDefaultDestination();
        void this.loadRoute();
      }
    });
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

  destinations() {
    const categories: Category[] =
      this.content().category === 'MOVIE' ? ['MOVIE'] : ['SERIES', 'ANIME'];
    return categories.map((category) => ({
      label: category,
      items: (this.organization?.categories[category].destinations ?? []).map((destination) => ({
        ...destination,
        category,
      })),
    }));
  }
  hasDestinations(): boolean {
    return this.destinations().some((group) => group.items.length > 0);
  }
  selectDefaultDestination(): void {
    const category = this.content().category;
    const id = this.organization?.categories[category].defaultDestinationId;
    this.destination =
      this.destinations()
        .flatMap((group) => group.items)
        .find((item) => item.category === category && item.id === id) ?? null;
  }
  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const [addons, settings] = await Promise.all([
        this.api.get<Addons>('/addons'),
        this.api.get<Settings>('/settings'),
      ]);
      this.sources = addons.search
        .filter((item) => item.enabled)
        .sort((a, b) => a.position - b.position);
      this.organization = settings.organization;
    });
    if (!this.organization) return;
    this.selectDefaultDestination();
    this.initialized = true;
    combineLatest([this.route.queryParamMap])
      .pipe(skip(1), debounceTime(0), takeUntilDestroyed(this.destroyRef))
      .subscribe(() => void this.loadRoute());
    await this.loadRoute();
  }
  private async loadRoute(): Promise<void> {
    await this.run(async (operation) => {
      this.result.set(null);
      this.release = null;
      const query = this.route.snapshot.queryParamMap;
      this.addonId = query.get('source') ?? this.sources[0]?.id ?? '';
      if (!this.sources.some((source) => source.id === this.addonId)) {
        throw { error: { error: 'KZ.RELEASE_SOURCE_UNAVAILABLE' } };
      }
      const page = Number(query.get('page') ?? 1);
      if (!Number.isSafeInteger(page) || page < 1) {
        throw { error: { error: 'KZ.RELEASE_CONTEXT_INVALID' } };
      }
      await this.loadReleases(false, page, operation);
    });
  }
  async findReleases(): Promise<void> {
    if (this.busy() || !this.content()) return;
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
      this.content(),
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
      queryParamsHandling: 'merge',
      queryParams: { page: page === 1 ? null : page },
    });
  }
  async selectSource(id: string | number | undefined): Promise<void> {
    if (typeof id !== 'string' || id === this.addonId || this.busy()) return;
    await this.router.navigate([], {
      relativeTo: this.route,
      queryParamsHandling: 'merge',
      queryParams: { source: id, page: null },
    });
  }
  async submit(): Promise<void> {
    if (this.busy()) return;
    const content = this.content();
    if (!content || !this.release || !this.destination) return;
    await this.run(async () => {
      await this.api.post('/downloads', {
        content: { ...content, category: this.destination!.category },
        release: this.release,
        destinationId: this.destination!.id,
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
