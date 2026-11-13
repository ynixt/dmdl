import { Component, input, model } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';

@Component({
  selector: 'app-stream-badge-fields',
  imports: [FormsModule, TranslatePipe, ButtonModule],
  template: `
    <div class="badge-fields">
      <h3>{{ 'KZ.STREAM_BADGES' | translate }}</h3>
      <p>{{ 'KZ.STREAM_BADGES_HELP' | translate }}</p>
      <label [for]="prefix() + '-badge-json'">{{ 'KZ.STREAM_BADGE_JSON' | translate }}</label>
      <textarea
        [id]="prefix() + '-badge-json'"
        [ngModel]="value()"
        (ngModelChange)="value.set($event)"
        rows="12"
        spellcheck="false"
        autocapitalize="off"
        [attr.aria-describedby]="prefix() + '-badge-hint'"
        [disabled]="disabled()"
      ></textarea>
      <small [id]="prefix() + '-badge-hint'">{{
        'KZ.STREAM_BADGES_IMPORT_HELP' | translate
      }}</small>
      <p-button
        [label]="'KZ.RESTORE_BADGE_DEFAULTS' | translate"
        icon="pi pi-refresh"
        severity="secondary"
        [outlined]="true"
        [disabled]="disabled()"
        (onClick)="value.set(defaultValue())"
      />
    </div>
  `,
  styles: `
    .badge-fields {
      display: grid;
      gap: 0.75rem;
      min-width: 0;
    }
    h3,
    p {
      margin: 0;
    }
    p,
    small {
      opacity: 0.8;
      line-height: 1.5;
    }
    textarea {
      width: 100%;
      min-width: 0;
      box-sizing: border-box;
      resize: vertical;
      border: 1px solid var(--p-content-border-color);
      border-radius: 8px;
      padding: 0.75rem;
      background: var(--p-content-background);
      color: inherit;
      font-family: monospace;
      font-size: 0.8rem;
    }
  `,
})
export class StreamBadgeFieldsComponent {
  readonly value = model('');
  readonly defaultValue = input('');
  readonly disabled = input(false);
  readonly prefix = input('settings');
}
