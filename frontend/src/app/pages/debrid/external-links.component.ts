import { Component, computed, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { Category } from '../../services/debrid-api.service';
import { externalLinks } from './external-links';

/** Buttons opening the title on each database it has a mapped id for. */
@Component({
  selector: 'app-external-links',
  imports: [ButtonModule, TranslatePipe],
  template: `@if (links().length) {
    <nav class="external-links" [attr.aria-label]="'KZ.EXTERNAL_LINKS' | translate">
      @for (link of links(); track link.provider) {
        <a
          pButton
          size="small"
          severity="secondary"
          [outlined]="true"
          [href]="link.url"
          target="_blank"
          rel="noopener noreferrer"
          [attr.data-provider]="link.provider"
        >
          {{ link.label }}
          <i class="pi pi-external-link" aria-hidden="true"></i>
        </a>
      }
    </nav>
  }`,
  styles: [
    `
      .external-links {
        display: flex;
        flex-wrap: wrap;
        gap: 0.5rem;
      }
      .pi {
        font-size: 0.75rem;
      }
    `,
  ],
})
export class ExternalLinksComponent {
  readonly mappedIds = input<Record<string, string> | undefined>();
  readonly category = input<Category | null>(null);
  readonly links = computed(() => externalLinks(this.mappedIds(), this.category()));
}
