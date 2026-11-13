import { ChangeDetectionStrategy, Component, computed, input, signal } from '@angular/core';

export function flagParts(text: string): { text: string; country?: string }[] {
  const parts: { text: string; country?: string }[] = [];
  let offset = 0;
  for (const match of text.matchAll(/[\u{1F1E6}-\u{1F1FF}]{2}|💃(?:[\u{1F3FB}-\u{1F3FF}])?/gu)) {
    if (match.index > offset) parts.push({ text: text.slice(offset, match.index) });
    const country = match[0].startsWith('💃')
      ? 'latam'
      : [...match[0]]
          .map((character) => String.fromCharCode(character.codePointAt(0)! - 0x1f1e6 + 97))
          .join('');
    parts.push({ text: country.toUpperCase(), country });
    offset = match.index + match[0].length;
  }
  if (offset < text.length) parts.push({ text: text.slice(offset) });
  return parts;
}

@Component({
  selector: 'app-flag-text',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@for (part of parts(); track $index) {
    @if (part.country && !failed().has(part.country)) {
      <span class="flag">
        <img
          class="country-flag"
          [src]="'/flags/' + part.country + '.svg'"
          [alt]="part.country === 'latam' ? '' : part.text"
          [attr.aria-hidden]="part.country === 'latam' ? 'true' : null"
          [title]="part.text"
          (error)="imageFailed(part.country)"
        />
        @if (part.country === 'latam') {
          <span class="region-label">LATAM</span>
        }
      </span>
    } @else {
      <ng-container>{{ part.text }}</ng-container>
    }
  }`,
  styles: `
    :host {
      white-space: inherit;
      overflow-wrap: anywhere;
    }
    .country-flag {
      display: inline-block;
      width: 1.35em;
      height: 1em;
      object-fit: contain;
      vertical-align: -0.1em;
      border-radius: 3px;
    }
    .flag {
      display: inline-flex;
      align-items: center;
      vertical-align: -0.1em;
      white-space: nowrap;
    }
    .region-label {
      margin-inline-start: 0.25em;
      font-size: 0.75em;
    }
  `,
})
export class FlagTextComponent {
  readonly text = input('');
  readonly parts = computed(() => flagParts(this.text()));
  readonly failed = signal(new Set<string>());
  imageFailed(country: string): void {
    this.failed.update((failed) => new Set([...failed, country]));
  }
}
