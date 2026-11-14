import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import { SearchAddonInput } from '../../services/debrid-api.service';
import { SettingsDraftStore } from './settings-draft.store';
import { SettingsFeedbackComponent } from './settings-feedback.component';
import { StreamBadgeFieldsComponent } from './stream-badge-fields.component';

@Component({
  selector: 'app-settings-search-tab',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    SecretInputComponent,
    SettingsFeedbackComponent,
    StreamBadgeFieldsComponent,
  ],
  styleUrls: ['./debrid.scss', './wizard.scss'],
  template: `
    <app-settings-feedback [error]="slice.error()" [message]="slice.message()" />
    <fieldset class="wizard-fields" [disabled]="slice.saving()" [attr.aria-busy]="slice.saving()">
      <h3>{{ 'KZ.SEARCH_ADDONS' | translate }}</h3>
      @if (slice.errors()['search']; as error) {
        <p role="alert" class="error">{{ error | translate }}</p>
      }
      @for (addon of slice.draft().addons; track $index; let index = $index) {
        <div class="source-fields">
          <div class="field">
            <label [for]="'search-name-' + index">{{ 'KZ.ADDON_NAME' | translate }}</label>
            <input
              pInputText
              [id]="'search-name-' + index"
              [ngModel]="addon.name"
              (ngModelChange)="edit(index, { name: $event })"
            />
          </div>
          <div class="field">
            <label [for]="'search-url-' + index">{{ 'KZ.MANIFEST_URL' | translate }}</label>
            <app-secret-input
              [inputId]="'search-url-' + index"
              kind="secret"
              [ngModel]="addon.manifestUrl"
              (ngModelChange)="edit(index, { manifestUrl: $event ?? '' })"
              [disabled]="slice.saving()"
              [invalid]="!!slice.errors()['search.' + index + '.manifestUrl']"
              [describedBy]="'search-url-error-' + index"
            />
            @if (slice.errors()['search.' + index + '.manifestUrl']; as error) {
              <p [id]="'search-url-error-' + index" role="alert" class="error">
                {{ error | translate }}
              </p>
            }
          </div>
          <label class="inline-choice"
            ><input
              type="checkbox"
              [ngModel]="addon.enabled"
              (ngModelChange)="edit(index, { enabled: $event })"
            />{{ 'KZ.ENABLED' | translate }}</label
          >
          <div class="row">
            <p-button
              icon="pi pi-arrow-up"
              [ariaLabel]="'KZ.MOVE_UP' | translate"
              (onClick)="move(index, -1)"
              [disabled]="index === 0 || slice.saving()"
            />
            <p-button
              icon="pi pi-arrow-down"
              [ariaLabel]="'KZ.MOVE_DOWN' | translate"
              (onClick)="move(index, 1)"
              [disabled]="index === slice.draft().addons.length - 1 || slice.saving()"
            />
            <p-button
              [label]="'KZ.REMOVE_ADDON' | translate"
              severity="danger"
              [outlined]="true"
              (onClick)="remove(index)"
              [disabled]="slice.saving()"
            />
          </div>
        </div>
      }
      <p-button
        [label]="'KZ.ADD_ADDON' | translate"
        icon="pi pi-plus"
        severity="secondary"
        [outlined]="true"
        (onClick)="add()"
        [disabled]="slice.saving()"
      />
      <p class="hint">{{ 'KZ.MANIFEST_URL_HELP' | translate }}</p>
      <app-stream-badge-fields
        [value]="slice.draft().streamBadgeJson"
        (valueChange)="slice.patch({ streamBadgeJson: $event })"
        [defaultValue]="store.defaultBadges()"
        [disabled]="slice.saving()"
      />
      @if (slice.errors()['search.streamBadgeJson']; as error) {
        <p role="alert" class="error">{{ error | translate }}</p>
      }
    </fieldset>
    <footer class="wizard-footer">
      <p-button
        [label]="'KZ.SAVE_SETTINGS' | translate"
        icon="pi pi-save"
        [disabled]="!slice.dirty() || slice.saving()"
        [loading]="slice.saving()"
        (onClick)="store.saveSearch()"
      />
    </footer>
  `,
})
export class SettingsSearchTabComponent {
  readonly store = inject(SettingsDraftStore);
  readonly slice = this.store.search;
  edit(index: number, value: Partial<SearchAddonInput>): void {
    this.slice.patch({
      addons: this.slice.draft().addons.map((row, i) => (i === index ? { ...row, ...value } : row)),
    });
  }
  add(): void {
    this.slice.patch({
      addons: [...this.slice.draft().addons, { name: '', manifestUrl: '', enabled: true }],
    });
    this.slice.errors.set({});
  }
  remove(index: number): void {
    this.slice.patch({ addons: this.slice.draft().addons.filter((_, i) => i !== index) });
    this.slice.errors.set({});
  }
  move(index: number, direction: number): void {
    const addons = [...this.slice.draft().addons];
    const other = index + direction;
    if (other < 0 || other >= addons.length) return;
    [addons[index], addons[other]] = [addons[other], addons[index]];
    this.slice.patch({ addons });
    this.slice.errors.set({});
  }
}
