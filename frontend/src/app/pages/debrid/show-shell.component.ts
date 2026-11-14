import { Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink, RouterOutlet } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { DebridApi, Metadata } from '../../services/debrid-api.service';
import { BackLinkComponent } from './back-link.component';
import { MediaHeroComponent } from './media-hero.component';
import { categoryPrefix, metadataCategory, navigationPreview } from './media-route';

@Component({
  selector: 'app-show-shell',
  imports: [RouterOutlet, RouterLink, TranslatePipe, BackLinkComponent, MediaHeroComponent],
  template: `@if (metadata()) {
      <router-outlet />
    } @else {
      <main class="page">
        <app-back-link [fallback]="['/discovery']" />
        @if (error()) {
          <p role="alert" class="error">{{ error() | translate }}</p>
          <a routerLink="/discovery">{{ 'KZ.BACK_TO_DISCOVERY' | translate }}</a>
        } @else {
          @if (preview(); as item) {
            <!-- The card already knew these; show them while the seasons load. -->
            <app-media-hero
              [image]="item.posterUrl || item.background"
              [title]="item.title"
              [subtitle]="item.year"
              [description]="item.description"
            />
          }
          <p role="status">{{ 'KZ.LOADING' | translate }}</p>
        }
      </main>
    }`,
  styleUrls: ['./debrid.scss', './media-pages.scss'],
})
export class ShowShell {
  private readonly api = inject(DebridApi);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private operation = 0;
  private loadedId: string | null = null;
  readonly metadata = signal<Metadata | null>(null);
  readonly preview = signal<Metadata | null>(navigationPreview(inject(Router)));
  readonly error = signal('');
  readonly reloading = signal<'seasons' | number | null>(null);

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      // `/show/:id` → `/anime/:id` reuses this shell; only a different title needs loading.
      const id = params.get('id');
      if (id === this.loadedId) return;
      this.loadedId = id;
      this.metadata.set(null);
      void this.load(false);
    });
  }
  async reload(location: 'seasons' | number = 'seasons'): Promise<void> {
    if (this.reloading() !== null) return;
    this.reloading.set(location);
    await this.load(true);
  }
  private async load(forceRefresh: boolean): Promise<void> {
    const operation = ++this.operation;
    this.error.set('');
    try {
      // The backend finds the addon type itself: `/anime` titles are often `series` to the addon.
      const query = new URLSearchParams({
        kind: 'show',
        contentId: this.route.snapshot.paramMap.get('id') ?? '',
      });
      if (forceRefresh) query.set('forceRefresh', 'true');
      const item = await this.api.get<Metadata>('/discovery/meta?' + query);
      if (operation !== this.operation) return;
      const category = metadataCategory(item);
      if (category !== 'SERIES' && category !== 'ANIME')
        throw { error: { error: 'KZ.UNSUPPORTED_TYPE' } };
      this.metadata.set(item);
      const prefix = categoryPrefix(category).slice(1);
      const tree = this.router.parseUrl(this.router.url);
      const segments = tree.root.children['primary']?.segments;
      if (segments?.length && segments[0].path !== prefix) {
        segments[0].path = prefix;
        await this.router.navigateByUrl(tree, { replaceUrl: true });
      }
    } catch (error) {
      if (operation === this.operation)
        this.error.set(
          (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
        );
    } finally {
      if (operation === this.operation) this.reloading.set(null);
    }
  }
}
