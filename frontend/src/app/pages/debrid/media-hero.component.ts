import { Component, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { RatingSource } from '../../services/debrid-api.service';

/** Database names are brands, not translated. */
const SOURCE_LABELS: Record<RatingSource, string> = { IMDB: 'IMDb', KITSU: 'Kitsu' };

@Component({
  selector: 'app-media-hero',
  imports: [TranslatePipe],
  template: `<section class="media-hero">
    @if (image()) {
      <img [src]="image()" [alt]="title()" />
    }
    <div class="body">
      <header class="heading">
        <div class="titles">
          <h1>{{ title() }}</h1>
          <p class="subtitle">{{ subtitle() }}</p>
        </div>
        @if (rating()) {
          <div class="rating" role="group" [attr.aria-label]="'KZ.RATING' | translate">
            <span class="score"
              ><i class="pi pi-star-fill" aria-hidden="true"></i> {{ rating() }}</span
            >
            <small class="source">{{ sourceLabel() ?? ('KZ.RATING_NO_SOURCE' | translate) }}</small>
          </div>
        }
      </header>
      @if (description()) {
        <p class="description">{{ description() }}</p>
      }
      <ng-content />
    </div>
  </section>`,
  styles: [
    `
      .media-hero {
        display: flex;
        align-items: start;
        gap: 2rem;
        margin-bottom: 2rem;
      }
      img {
        width: min(30%, 220px);
        max-height: 330px;
        object-fit: contain;
        border-radius: 0.75rem;
      }
      /* One gap spaces every block, including content projected by the pages. */
      .body {
        flex: 1;
        min-width: 0;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 1rem;
      }
      .body > *,
      .body > ::ng-deep * {
        margin: 0;
      }
      .heading {
        align-self: stretch;
        display: flex;
        align-items: stretch;
        justify-content: space-between;
        gap: 1rem;
      }
      .titles {
        min-width: 0;
      }
      h1 {
        margin: 0;
      }
      .subtitle {
        margin: 0.25rem 0 0;
        color: var(--p-text-muted-color);
      }
      /* Matches the height of the title and year beside it. */
      .rating {
        flex-shrink: 0;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 0.125rem;
        padding: 0 0.875rem;
        border: 1px solid var(--p-content-border-color, currentColor);
        border-radius: 0.5rem;
      }
      .score {
        font-size: 1.25rem;
        font-weight: 600;
        line-height: 1.2;
      }
      .score .pi {
        color: var(--p-yellow-500, #eab308);
        font-size: 1rem;
      }
      .source {
        font-size: 0.75rem;
        color: var(--p-text-muted-color);
      }
      .description {
        white-space: pre-line;
        line-height: 1.6;
      }
      @media (max-width: 600px) {
        .media-hero {
          gap: 1rem;
          flex-wrap: wrap;
        }
        img {
          width: 140px;
        }
      }
    `,
  ],
})
export class MediaHeroComponent {
  readonly image = input<string | null>();
  readonly title = input.required<string>();
  readonly subtitle = input<string | number | null>();
  readonly description = input<string | null>();
  /** Score as the addon sent it; no rating block without one. */
  readonly rating = input<string | null>();
  /** `null`/absent shows "No source": a rating's source is only named when the addon states it. */
  readonly ratingSource = input<RatingSource | null>();
  sourceLabel(): string | null {
    const source = this.ratingSource();
    return source ? SOURCE_LABELS[source] : null;
  }
}
