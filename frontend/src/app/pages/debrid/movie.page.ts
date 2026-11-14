import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { DebridApi, Metadata } from '../../services/debrid-api.service';
import { BackLinkComponent } from './back-link.component';
import { ExternalLinksComponent } from './external-links.component';
import { MediaHeroComponent } from './media-hero.component';
import { mediaContent, navigationPreview } from './media-route';
import { ReleasesPanelComponent } from './releases-panel.component';

@Component({
  selector: 'app-movie',
  imports: [
    ExternalLinksComponent,
    BackLinkComponent,
    MediaHeroComponent,
    ReleasesPanelComponent,
    TranslatePipe,
    RouterLink,
  ],
  template: `<main class="page">
    <app-back-link [fallback]="['/discovery']" />
    @if (error()) {
      <p role="alert" class="error">{{ error() | translate }}</p>
      <a routerLink="/discovery">{{ 'KZ.BACK_TO_DISCOVERY' | translate }}</a>
    } @else if (metadata(); as item) {
      <app-media-hero
        [image]="item.posterUrl || item.background"
        [title]="item.title"
        [subtitle]="item.year"
        [description]="item.description"
        [rating]="item.rating"
        [ratingSource]="item.ratingSource"
      >
        <app-external-links [mappedIds]="item.identity.mappedIds" category="MOVIE" />
      </app-media-hero>
      <app-releases-panel [content]="content()!" />
    } @else {
      @if (preview(); as item) {
        <app-media-hero
          [image]="item.posterUrl || item.background"
          [title]="item.title"
          [subtitle]="item.year"
          [description]="item.description"
        />
      }
      <p role="status">{{ 'KZ.LOADING' | translate }}</p>
    }
  </main>`,
  styleUrl: './debrid.scss',
})
export class MoviePage {
  private readonly api = inject(DebridApi);
  private readonly route = inject(ActivatedRoute);
  private operation = 0;
  readonly metadata = signal<Metadata | null>(null);
  /** The card's data, shown while the full metadata loads. */
  readonly preview = signal<Metadata | null>(navigationPreview(inject(Router)));
  readonly error = signal('');
  readonly content = computed(() => (this.metadata() ? mediaContent(this.metadata()!) : null));

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      void this.load(params.get('id') ?? '');
    });
  }
  private async load(id: string): Promise<void> {
    const operation = ++this.operation;
    this.metadata.set(null);
    this.error.set('');
    try {
      const item = await this.api.get<Metadata>(
        '/discovery/meta?' + new URLSearchParams({ kind: 'movie', contentId: id }),
      );
      if (operation !== this.operation) return;
      if (item.identity.resourceType !== 'movie') throw { error: { error: 'KZ.UNSUPPORTED_TYPE' } };
      this.metadata.set(item);
    } catch (error) {
      if (operation === this.operation)
        this.error.set(
          (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
        );
    }
  }
}
