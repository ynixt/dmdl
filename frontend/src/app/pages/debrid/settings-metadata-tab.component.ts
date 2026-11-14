import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import { SettingsDraftStore } from './settings-draft.store';
import { SettingsFeedbackComponent } from './settings-feedback.component';

@Component({
  selector: 'app-settings-metadata-tab',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    SelectModule,
    SecretInputComponent,
    SettingsFeedbackComponent,
  ],
  styleUrls: ['./debrid.scss', './wizard.scss'],
  template: `
    <app-settings-feedback [error]="slice.error()" [message]="slice.message()" />
    <fieldset class="wizard-fields" [disabled]="slice.saving()" [attr.aria-busy]="slice.saving()">
      <div class="field">
        <label for="language">{{ 'KZ.LANGUAGE' | translate }}</label>
        <p-select
          inputId="language"
          [options]="languages"
          [ngModel]="slice.draft().locale"
          (ngModelChange)="slice.patch({ locale: $event })"
          [disabled]="slice.saving()"
        />
      </div>
      <h3>{{ 'KZ.METADATA_ADDON' | translate }}</h3>
      <div class="field">
        <label for="metadata-name">{{ 'KZ.ADDON_NAME' | translate }}</label>
        <input
          pInputText
          id="metadata-name"
          [ngModel]="slice.draft().addon.name"
          (ngModelChange)="changeAddon('name', $event)"
        />
      </div>
      <div class="field">
        <label for="metadata-url">{{ 'KZ.MANIFEST_URL' | translate }}</label>
        <app-secret-input
          inputId="metadata-url"
          kind="secret"
          [ngModel]="slice.draft().addon.manifestUrl"
          (ngModelChange)="changeAddon('manifestUrl', $event)"
          [disabled]="slice.saving()"
          [invalid]="!!slice.errors()['metadata.manifestUrl']"
          describedBy="metadata-url-error"
        />
        @if (slice.errors()['metadata.manifestUrl']; as error) {
          <p id="metadata-url-error" role="alert" class="error">{{ error | translate }}</p>
        }
      </div>
      <p class="hint">{{ 'KZ.MANIFEST_URL_HELP' | translate }}</p>
    </fieldset>
    <footer class="wizard-footer">
      <p-button
        [label]="'KZ.SAVE_SETTINGS' | translate"
        icon="pi pi-save"
        [disabled]="!slice.dirty() || slice.saving()"
        [loading]="slice.saving()"
        (onClick)="store.saveMetadata()"
      />
    </footer>
  `,
})
export class SettingsMetadataTabComponent {
  readonly store = inject(SettingsDraftStore);
  readonly slice = this.store.metadata;
  readonly languages = ['pt-BR', 'en-US'];
  changeAddon(field: 'name' | 'manifestUrl', value: string | null): void {
    this.slice.patch({ addon: { ...this.slice.draft().addon, [field]: value ?? '' } });
  }
}
