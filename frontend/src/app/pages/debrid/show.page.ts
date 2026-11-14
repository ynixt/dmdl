import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { AccordionModule } from 'primeng/accordion';
import { ButtonModule } from 'primeng/button';
import { BackLinkComponent } from './back-link.component';
import { ExternalLinksComponent } from './external-links.component';
import { MediaHeroComponent } from './media-hero.component';
import { metadataCategory } from './media-route';
import { ShowShell } from './show-shell.component';

@Component({
  selector: 'app-show',
  imports: [
    ExternalLinksComponent,
    AccordionModule,
    ButtonModule,
    BackLinkComponent,
    MediaHeroComponent,
    TranslatePipe,
    RouterLink,
  ],
  template: `<main class="page">
    <app-back-link [fallback]="['/discovery']" />
    @if (shell.metadata(); as item) {
      <app-media-hero
        [image]="item.posterUrl || item.background"
        [title]="item.title"
        [subtitle]="item.year"
        [description]="item.description"
        [rating]="item.rating"
        [ratingSource]="item.ratingSource"
      >
        <app-external-links [mappedIds]="item.identity.mappedIds" [category]="category(item)" />
        <p-button
          class="reload-seasons"
          [label]="'KZ.RELOAD_SEASONS' | translate"
          icon="pi pi-refresh"
          [loading]="shell.reloading() === 'seasons'"
          [disabled]="shell.reloading() !== null"
          (onClick)="shell.reload()"
        />
      </app-media-hero>
      @if (shell.error()) {
        <p role="alert" class="error">{{ shell.error() | translate }}</p>
      }
      <p-accordion [value]="expanded()" (valueChange)="expand($event)">
        @for (season of seasons(); track season) {
          <p-accordion-panel [value]="season">
            <p-accordion-header>
              <span class="season-header">
                @if (seasonPoster(season); as poster) {
                  <img class="season-poster" [src]="poster" alt="" loading="lazy" />
                }
                <span>{{
                  season === 0
                    ? ('KZ.SPECIALS' | translate)
                    : ('KZ.SEASON_NUMBER' | translate: { number: season })
                }}</span>
              </span>
            </p-accordion-header>
            <p-accordion-content>
              @if (expanded() === season) {
                <div class="season-actions">
                  <p-button
                    [label]="'KZ.RELOAD_EPISODES' | translate"
                    icon="pi pi-refresh"
                    [loading]="shell.reloading() === season"
                    [disabled]="shell.reloading() !== null"
                    (onClick)="shell.reload(season)"
                  />
                </div>
                <div class="episode-list">
                  @for (video of episodes(season); track video.id) {
                    <a
                      class="choice episode"
                      [routerLink]="[season, video.episode]"
                      title="E{{ video.episode }} — {{ video.title }}"
                    >
                      @if (video.thumbnail) {
                        <img [src]="video.thumbnail" [alt]="video.title || ''" loading="lazy" />
                      }
                      <span>E{{ video.episode }} — {{ video.title }}</span>
                    </a>
                  }
                </div>
              }
            </p-accordion-content>
          </p-accordion-panel>
        } @empty {
          <p>{{ 'KZ.NO_EPISODES' | translate }}</p>
        }
      </p-accordion>
    }
  </main>`,
  styleUrls: ['./debrid.scss', './media-pages.scss'],
})
export class ShowPage {
  readonly category = metadataCategory;
  readonly shell = inject(ShowShell);
  private readonly router = inject(Router);
  private readonly route = inject(ActivatedRoute);
  readonly expanded = signal<number | undefined>(undefined);
  readonly seasons = computed(() =>
    [...new Set(this.shell.metadata()?.videos.map((video) => video.season) ?? [])].sort(
      (a, b) => a - b,
    ),
  );
  constructor() {
    this.route.queryParamMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      const season = params.get('season');
      this.expanded.set(season !== null && /^\d+$/.test(season) ? Number(season) : undefined);
    });
  }
  seasonPoster(season: number): string | null {
    return this.shell.metadata()?.seasonPosters?.[String(season)] ?? null;
  }
  episodes(season: number) {
    return (this.shell.metadata()?.videos ?? [])
      .filter((video) => video.season === season)
      .sort((a, b) => a.episode - b.episode);
  }
  expand(value: string | number | string[] | number[] | undefined | null): void {
    const season = typeof value === 'number' ? value : null;
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParamsHandling: 'merge',
      queryParams: { season },
    });
  }
}
