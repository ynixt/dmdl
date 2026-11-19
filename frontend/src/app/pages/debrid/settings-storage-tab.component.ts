import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { SelectModule } from '@openng/optimus-ui/select';
import { Category, Content, DebridApi, Organization } from '../../services/debrid-api.service';
import { SettingsDraftStore } from './settings-draft.store';
import { SettingsFeedbackComponent } from './settings-feedback.component';
import { StorageFieldsComponent } from './storage-fields.component';
import { TransferFieldsComponent } from './transfer-fields.component';
import { Aria2RestartComponent } from './aria2-restart.component';
import { FinalizationFieldsComponent } from './finalization-fields.component';

@Component({
  selector: 'app-settings-storage-tab',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    SelectModule,
    SettingsFeedbackComponent,
    StorageFieldsComponent,
    TransferFieldsComponent,
    Aria2RestartComponent,
    FinalizationFieldsComponent,
  ],
  styleUrls: ['./debrid.scss', './wizard.scss'],
  template: `
    <app-settings-feedback [error]="slice.error()" [message]="slice.message()" />
    <fieldset class="wizard-fields" [disabled]="slice.saving()" [attr.aria-busy]="slice.saving()">
      <h3>{{ 'KZ.ORGANIZATION' | translate }}</h3>
      <p>{{ 'KZ.NEW_REQUESTS_ONLY' | translate }}</p>
      <p class="hint">{{ 'KZ.PLACEHOLDERS' | translate }}</p>
      <app-storage-fields
        [organization]="slice.draft().organization"
        [disabled]="slice.saving()"
        (changed)="slice.touch(); previewPath.set('')"
      />
      <p>{{ 'KZ.ORIGINAL_FILENAME_HELP' | translate }}</p>
      <div class="form-row">
        <div class="field">
          <label for="preview-category">{{ 'KZ.PREVIEW' | translate }}</label>
          <p-select
            inputId="preview-category"
            [options]="categories"
            [(ngModel)]="previewCategory"
            [disabled]="slice.saving()"
          />
        </div>
        <p-button
          [label]="'KZ.PREVIEW' | translate"
          (onClick)="preview()"
          [disabled]="slice.saving()"
        />
        <p-button
          [label]="'KZ.RESTORE_DEFAULTS' | translate"
          severity="secondary"
          (onClick)="restore()"
          [disabled]="slice.saving()"
        />
      </div>
      @if (previewPath()) {
        <div class="naming-example">
          <code>{{ previewPath() }}</code>
          <p class="hint">{{ 'KZ.SAMPLE_EXTENSION' | translate }}</p>
        </div>
      }
      <h3>{{ 'KZ.TRANSFER_SETTINGS' | translate }}</h3>
      <app-transfer-fields
        [settings]="slice.draft()"
        [disabled]="slice.saving()"
        (changed)="slice.touch()"
      />
      <h3>{{ 'KZ.FINALIZATION' | translate }}</h3>
      <app-finalization-fields
        [checks]="slice.draft().finalization"
        [disabled]="slice.saving()"
        (changed)="slice.touch()"
      />
    </fieldset>
    <app-aria2-restart />
    <footer class="wizard-footer">
      <p-button
        [label]="'KZ.SAVE_SETTINGS' | translate"
        icon="pi pi-save"
        [disabled]="!slice.dirty() || slice.saving()"
        [loading]="slice.saving()"
        (onClick)="store.saveStorage()"
      />
    </footer>
  `,
})
export class SettingsStorageTabComponent {
  readonly store = inject(SettingsDraftStore);
  readonly slice = this.store.storage;
  private readonly api = inject(DebridApi);
  readonly categories: Category[] = ['MOVIE', 'SERIES', 'ANIME'];
  previewCategory: Category = 'MOVIE';
  readonly previewPath = signal('');

  restore(): Promise<void> {
    return this.store.run(
      this.slice,
      async () => {
        this.slice.patch({
          organization: await this.api.get<Organization>('/settings/organization/defaults'),
        });
        this.previewPath.set('');
      },
      '',
    );
  }

  preview(): Promise<void> {
    const episodic = this.previewCategory !== 'MOVIE';
    const content: Content = {
      identity: {
        sourceId: '00000000-0000-0000-0000-000000000001',
        sourceRevision: 1,
        resourceType: episodic ? 'series' : 'movie',
        contentId: 'preview',
        ...(episodic ? { videoId: 'preview:0:1' } : {}),
      },
      title: 'Example',
      year: 2024,
      category: this.previewCategory,
      ...(episodic ? { season: 0, episode: 1, episodeTitle: 'Arrival' } : {}),
    };
    return this.store.run(
      this.slice,
      async () => {
        const response = await this.api.post<{ path: string }>('/settings/organization/preview', {
          organization: this.slice.draft().organization,
          content,
          originalFileName: 'Example.Release.1080p.WEB-DL.H264-GROUP.mkv',
        });
        this.previewPath.set(response.path);
      },
      '',
    );
  }
}
