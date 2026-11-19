import { Component, Input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { InputTextModule } from '@openng/optimus-ui/inputtext';
import { SelectModule } from '@openng/optimus-ui/select';
import { Category, Organization, StorageDestination } from '../../services/debrid-api.service';
import { DirectoryPickerComponent } from './directory-picker.component';

@Component({
  selector: 'app-storage-fields',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    SelectModule,
    DirectoryPickerComponent,
  ],
  template: `
    <p class="hint">{{ 'KZ.STORAGE_CATEGORIES_HELP' | translate }}</p>
    <div class="category-settings">
      @for (category of categories; track category) {
        @let config = organization.categories[category];
        <fieldset class="storage-category">
          <legend>{{ 'KZ.CATEGORY_' + category | translate }}</legend>
          @for (destination of config.destinations; track destination.id) {
            <div class="destination-row">
              <div class="field">
                <label [for]="prefix + '-name-' + destination.id">{{
                  'KZ.DESTINATION_NAME' | translate
                }}</label>
                <input
                  pInputText
                  [id]="prefix + '-name-' + destination.id"
                  [(ngModel)]="destination.name"
                  (ngModelChange)="changed.emit()"
                />
              </div>
              <div class="field">
                <label [for]="prefix + '-path-' + destination.id">{{
                  'KZ.DESTINATION_PATH' | translate
                }}</label>
                <div class="path-control">
                  <input
                    pInputText
                    [id]="prefix + '-path-' + destination.id"
                    [(ngModel)]="destination.path"
                    (ngModelChange)="changed.emit()"
                    placeholder="/media/…"
                  />
                  <app-directory-picker
                    [path]="destination.path"
                    (selected)="selectPath(destination, $event)"
                  />
                </div>
              </div>
              <p-button
                [label]="'KZ.REMOVE_FOLDER' | translate"
                icon="pi pi-trash"
                severity="danger"
                [outlined]="true"
                (onClick)="remove(category, destination)"
              />
            </div>
          } @empty {
            <p class="hint">{{ 'KZ.NO_CATEGORY_DESTINATIONS' | translate }}</p>
          }
          <p-button
            [label]="'KZ.ADD_FOLDER' | translate"
            icon="pi pi-plus"
            severity="secondary"
            [outlined]="true"
            (onClick)="add(category)"
          />
          @if (config.destinations.length) {
            <div class="field">
              <label [for]="prefix + '-default-' + category">{{
                'KZ.DEFAULT_DESTINATION' | translate
              }}</label>
              <p-select
                [disabled]="disabled"
                [inputId]="prefix + '-default-' + category"
                [options]="config.destinations"
                optionLabel="name"
                optionValue="id"
                [(ngModel)]="config.defaultDestinationId"
                (ngModelChange)="changed.emit()"
              />
              <small class="hint">{{ 'KZ.DEFAULT_DESTINATION_HELP' | translate }}</small>
            </div>
            <div class="field">
              <label [for]="prefix + '-folder-' + category">{{
                'KZ.FOLDER_TEMPLATE' | translate
              }}</label>
              <input
                pInputText
                [id]="prefix + '-folder-' + category"
                [(ngModel)]="config.folderTemplate"
                (ngModelChange)="changed.emit()"
              />
            </div>
          }
        </fieldset>
      }
    </div>
  `,
  styleUrl: './debrid.scss',
})
export class StorageFieldsComponent {
  @Input() disabled = false;
  @Input({ required: true }) organization!: Organization;
  @Input() prefix = 'storage';
  readonly changed = output<void>();
  readonly categories: Category[] = ['MOVIE', 'SERIES', 'ANIME'];

  add(category: Category): void {
    const config = this.organization.categories[category];
    const destination = { id: crypto.randomUUID(), name: '', path: '' };
    config.destinations.push(destination);
    config.defaultDestinationId ??= destination.id;
    this.changed.emit();
  }

  remove(category: Category, destination: StorageDestination): void {
    const config = this.organization.categories[category];
    config.destinations = config.destinations.filter((entry) => entry.id !== destination.id);
    if (config.defaultDestinationId === destination.id)
      config.defaultDestinationId = config.destinations[0]?.id ?? null;
    this.changed.emit();
  }

  selectPath(destination: StorageDestination, path: string): void {
    destination.path = path;
    if (!destination.name.trim())
      destination.name = path.replace(/\\/g, '/').split('/').filter(Boolean).pop() ?? path;
    this.changed.emit();
  }
}
