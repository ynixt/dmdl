import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import { DebridApi } from '../../services/debrid-api.service';
import { SettingsDraftStore } from './settings-draft.store';
import { SettingsFeedbackComponent } from './settings-feedback.component';

@Component({
  selector: 'app-settings-jellyfin-tab',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    ToggleSwitchModule,
    SecretInputComponent,
    SettingsFeedbackComponent,
  ],
  styleUrls: ['./debrid.scss', './wizard.scss'],
  template: `
    <app-settings-feedback [error]="slice.error()" [message]="slice.message()" />
    <fieldset class="wizard-fields" [disabled]="slice.saving()" [attr.aria-busy]="slice.saving()">
      <p>{{ 'KZ.JELLYFIN_HELP' | translate }}</p>
      <div class="inline-choice">
        <p-toggleswitch
          inputId="jellyfin-enabled"
          [ngModel]="slice.draft().enabled"
          (ngModelChange)="slice.patch({ enabled: $event })"
          [disabled]="slice.saving()"
        />
        <label for="jellyfin-enabled">{{ 'KZ.ENABLED' | translate }}</label>
      </div>
      <div class="field">
        <label for="jellyfin-url">{{ 'KZ.SERVER_URL' | translate }}</label>
        <input
          pInputText
          id="jellyfin-url"
          [ngModel]="slice.draft().url"
          (ngModelChange)="slice.patch({ url: $event })"
          [attr.aria-invalid]="!!slice.errors()['jellyfin.url']"
          aria-describedby="jellyfin-url-error"
        />
        @if (slice.errors()['jellyfin.url']; as error) {
          <p id="jellyfin-url-error" role="alert" class="error">{{ error | translate }}</p>
        }
      </div>
      <div class="field">
        <label for="jellyfin-token">{{ 'KZ.API_TOKEN' | translate }}</label>
        <app-secret-input
          inputId="jellyfin-token"
          kind="secret"
          [ngModel]="slice.draft().token"
          (ngModelChange)="slice.patch({ token: $event ?? '' })"
          [disabled]="slice.saving()"
          [invalid]="!!slice.errors()['jellyfin.token']"
          describedBy="jellyfin-token-error"
        />
        @if (slice.errors()['jellyfin.token']; as error) {
          <p id="jellyfin-token-error" role="alert" class="error">{{ error | translate }}</p>
        }
      </div>
      <p-button
        [label]="'KZ.TEST_CONNECTION' | translate"
        icon="pi pi-check-circle"
        severity="secondary"
        (onClick)="testConnection()"
        [disabled]="slice.saving()"
      />
    </fieldset>
    <footer class="wizard-footer">
      <p-button
        [label]="'KZ.SAVE_SETTINGS' | translate"
        icon="pi pi-save"
        [disabled]="!slice.dirty() || slice.saving()"
        [loading]="slice.saving()"
        (onClick)="store.saveJellyfin()"
      />
    </footer>
  `,
})
export class SettingsJellyfinTabComponent {
  readonly store = inject(SettingsDraftStore);
  readonly slice = this.store.jellyfin;
  private readonly api = inject(DebridApi);
  testConnection(): Promise<void> {
    const draft = structuredClone(this.slice.draft());
    return this.store.run(
      this.slice,
      async () => {
        await this.api.post('/settings/jellyfin/test', draft);
      },
      'KZ.CONNECTION_OK',
    );
  }
}
