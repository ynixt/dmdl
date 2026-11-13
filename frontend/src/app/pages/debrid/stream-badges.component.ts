import { ChangeDetectionStrategy, Component, input, signal } from '@angular/core';
import { StreamBadge } from '../../services/debrid-api.service';
import { FlagTextComponent } from './flag-text.component';

export function fusionColor(value: string, fallback: string): string {
  if (/^#[\da-f]{8}$/i.test(value)) return '#' + value.slice(3) + value.slice(1, 3);
  return /^#[\da-f]{6}$/i.test(value) ? value : fallback;
}

@Component({
  selector: 'app-stream-badges',
  imports: [FlagTextComponent],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <span class="badges">
      @for (badge of badges(); track $index) {
        <span
          class="badge"
          [style.background]="background(badge)"
          [style.color]="color(badge.textColor, 'inherit')"
          [style.border-color]="border(badge)"
          [title]="badge.name"
        >
          @if (badge.imageURL && !failedImages().has(badge.imageURL)) {
            <img
              [src]="badge.imageURL"
              [alt]="badge.name"
              loading="lazy"
              referrerpolicy="no-referrer"
              (error)="imageFailed(badge.imageURL)"
            />
          } @else {
            <app-flag-text [text]="badge.name" />
          }
        </span>
      }
    </span>
  `,
  styles: `
    :host {
      display: block;
    }
    .badges {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.4rem;
    }
    .badge {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      min-height: 1.6rem;
      border: 1px solid transparent;
      border-radius: 5px;
      padding: 0.15rem 0.4rem;
      font-size: 0.75rem;
      line-height: 1.2;
      white-space: normal;
    }
    img {
      display: block;
      height: 1.25rem;
      max-width: 9rem;
      object-fit: contain;
      border-radius: 5px;
    }
  `,
})
export class StreamBadgesComponent {
  readonly badges = input<StreamBadge[]>([]);
  readonly failedImages = signal(new Set<string>());
  readonly color = fusionColor;
  background(badge: StreamBadge): string {
    if (
      badge.imageURL &&
      !this.failedImages().has(badge.imageURL) &&
      badge.tagStyle.toLowerCase() !== 'filled'
    )
      return 'transparent';
    return badge.tagStyle.toLowerCase().includes('filled')
      ? fusionColor(badge.tagColor, 'transparent')
      : 'transparent';
  }
  border(badge: StreamBadge): string {
    return badge.tagStyle.toLowerCase().includes('border')
      ? fusionColor(badge.borderColor, 'currentColor')
      : 'transparent';
  }
  imageFailed(url: string): void {
    this.failedImages.update((failed) => new Set([...failed, url]));
  }
}
