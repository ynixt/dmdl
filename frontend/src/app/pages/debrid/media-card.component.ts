import { Component, input, output } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { SkeletonModule } from 'primeng/skeleton';

@Component({
  selector: 'app-media-card',
  imports: [TranslatePipe, SkeletonModule],
  template: `<button
    class="media-card"
    [disabled]="disabled() || loading()"
    [attr.aria-hidden]="loading() ? true : null"
    (click)="open.emit()"
    [attr.title]="loading() ? null : disabled() ? ('KZ.UNSUPPORTED_TYPE' | translate) : title()"
  >
    <div class="poster">
      @if (loading()) {
        <p-skeleton width="100%" height="100%" />
      } @else if (poster()) {
        <img [src]="poster()" [alt]="title()" loading="lazy" />
      }
    </div>
    <strong [class.loading-title]="loading()">
      @if (loading()) {
        <p-skeleton width="100%" height="0.9em" />
        <p-skeleton width="70%" height="0.9em" />
      } @else {
        {{ title() }}
      }
    </strong>
    <span class="year">
      @if (loading()) {
        <p-skeleton width="3rem" height="1em" />
      } @else {
        {{ year() }}
      }
    </span>
  </button>`,
  styles: [
    `
      :host {
        display: block;
        height: 100%;
        min-width: 0;
      }
      button {
        width: 100%;
        height: 100%;
        display: flex;
        flex-direction: column;
        gap: 0.5rem;
        text-align: start;
        padding: 1rem;
        border: 1px solid var(--p-content-border-color);
        border-radius: 0.75rem;
        background: var(--p-content-background);
        color: inherit;
        cursor: pointer;
      }
      button:disabled {
        opacity: 0.6;
        cursor: default;
      }
      .poster {
        width: 100%;
        aspect-ratio: 2/3;
        flex-shrink: 0;
        overflow: hidden;
        border-radius: 0.5rem;
        background: var(--p-content-hover-background);
      }
      img {
        display: block;
        width: 100%;
        height: 100%;
        object-fit: cover;
      }
      strong {
        display: -webkit-box;
        -webkit-box-orient: vertical;
        -webkit-line-clamp: 2;
        min-height: 2.6em;
        line-height: 1.3;
        overflow: hidden;
        overflow-wrap: anywhere;
      }
      .year {
        min-height: 1.3em;
        line-height: 1.3;
        margin-top: auto;
      }
      .loading-title {
        display: flex;
        flex-direction: column;
        justify-content: space-evenly;
        height: 2.6em;
        flex-shrink: 0;
      }
      p-skeleton {
        display: block;
      }
      .poster p-skeleton {
        height: 100%;
      }
    `,
  ],
})
export class MediaCardComponent {
  readonly loading = input(false);
  readonly poster = input<string | null>();
  readonly title = input.required<string>();
  readonly year = input<number | null>();
  readonly disabled = input(false);
  readonly open = output<void>();
}
