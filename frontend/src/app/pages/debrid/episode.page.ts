import { DatePipe } from '@angular/common';
import { Component, computed, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { BackLinkComponent } from './back-link.component';
import { MediaHeroComponent } from './media-hero.component';
import { mediaContent } from './media-route';
import { ReleasesPanelComponent } from './releases-panel.component';
import { ShowShell } from './show-shell.component';

@Component({
  selector: 'app-episode',
  imports: [
    BackLinkComponent,
    MediaHeroComponent,
    ReleasesPanelComponent,
    RouterLink,
    TranslatePipe,
    DatePipe,
  ],
  template: `<main class="page">
    <app-back-link [fallback]="parent()" [queryParams]="{ season: season() }" />
    @if (video(); as video) {
      <app-media-hero
        [image]="video.thumbnail"
        [title]="shell.metadata()!.title"
        [subtitle]="
          'S' + video.season + ' E' + video.episode + (video.title ? ' · ' + video.title : '')
        "
        [description]="video.overview"
        [rating]="video.rating"
      >
        @if (video.released) {
          <p>{{ 'KZ.AIR_DATE' | translate }}: {{ video.released | date: 'mediumDate' }}</p>
        }
      </app-media-hero>
      <app-releases-panel [content]="content()!" />
    } @else {
      <p role="alert" class="error">{{ 'KZ.EPISODE_NOT_FOUND' | translate }}</p>
      <a [routerLink]="parent()" [queryParams]="{ season: season() }">{{
        'KZ.BACK_TO_SHOW' | translate
      }}</a>
    }
  </main>`,
  styleUrl: './debrid.scss',
})
export class EpisodePage {
  readonly shell = inject(ShowShell);
  private readonly route = inject(ActivatedRoute);
  readonly season = signal('');
  readonly episode = signal('');
  readonly video = computed(() =>
    this.shell
      .metadata()
      ?.videos.find(
        (video) =>
          String(video.season) === this.season() && String(video.episode) === this.episode(),
      ),
  );
  readonly content = computed(() =>
    this.video() ? mediaContent(this.shell.metadata()!, this.video()) : null,
  );
  readonly parent = computed(() => [
    this.shell.metadata()?.category === 'ANIME' ? '/anime' : '/show',
    this.route.parent?.snapshot.paramMap.get('id') ?? '',
  ]);
  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed(inject(DestroyRef))).subscribe((params) => {
      this.season.set(params.get('season') ?? '');
      this.episode.set(params.get('episode') ?? '');
    });
  }
}
