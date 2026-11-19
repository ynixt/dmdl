import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ToggleSwitchModule } from '@openng/optimus-ui/toggleswitch';
import { FinalizationChecks } from '../../services/debrid-api.service';
import { InfoTipComponent } from './info-tip.component';

/** The three integrity checks run while a completed download is placed in its destination. */
@Component({
  selector: 'app-finalization-fields',
  imports: [FormsModule, TranslatePipe, ToggleSwitchModule, InfoTipComponent],
  styleUrl: './debrid.scss',
  template: `
    @let value = checks();
    <div class="stack finalization-fields">
      <p class="hint">{{ 'KZ.FINALIZATION_HELP' | translate }}</p>
      @for (check of fields; track check.key) {
        <div class="field-label">
          <p-toggleswitch
            [disabled]="disabled()"
            [inputId]="'finalization-' + check.key"
            [ngModel]="value[check.key]"
            (ngModelChange)="value[check.key] = $event; changed.emit()"
          />
          <label [for]="'finalization-' + check.key">{{ check.label | translate }}</label>
          <app-info-tip [text]="check.label + '_INFO'" [field]="check.label" />
        </div>
      }
      @if (!value.hashDownloadedFile && !value.verifyCopy && !value.verifyPlacedFile) {
        <p role="note" class="hint">{{ 'KZ.FINALIZATION_NONE_WARNING' | translate }}</p>
      }
    </div>
  `,
})
export class FinalizationFieldsComponent {
  readonly checks = input.required<FinalizationChecks>();
  readonly disabled = input(false);
  readonly changed = output<void>();
  readonly fields: { key: keyof FinalizationChecks; label: string }[] = [
    { key: 'hashDownloadedFile', label: 'KZ.FINALIZATION_HASH_DOWNLOAD' },
    { key: 'verifyCopy', label: 'KZ.FINALIZATION_VERIFY_COPY' },
    { key: 'verifyPlacedFile', label: 'KZ.FINALIZATION_VERIFY_PLACED' },
  ];
}
