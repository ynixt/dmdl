import { Component, Input, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { DialogModule } from 'primeng/dialog';
import { InputTextModule } from 'primeng/inputtext';
import { DebridApi, StorageDirectories } from '../../services/debrid-api.service';

@Component({
  selector: 'app-directory-picker',
  imports: [FormsModule, TranslatePipe, ButtonModule, DialogModule, InputTextModule],
  template: `
    <p-button
      icon="pi pi-folder-open"
      [ariaLabel]="'KZ.BROWSE_DIRECTORY' | translate"
      severity="secondary"
      [outlined]="true"
      (onClick)="open()"
    />
    <p-dialog
      [header]="'KZ.BROWSE_DIRECTORY' | translate"
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      [modal]="true"
      [style]="{ width: '42rem', maxWidth: '95vw' }"
    >
      <div class="directory-browser">
        <p class="hint">{{ 'KZ.SERVER_DIRECTORY_HELP' | translate }}</p>
        <form class="path-control" (ngSubmit)="load(location)">
          <input
            pInputText
            [attr.aria-label]="'KZ.DESTINATION_PATH' | translate"
            [(ngModel)]="location"
            name="location"
          />
          <p-button
            icon="pi pi-arrow-right"
            [ariaLabel]="'KZ.OPEN_DIRECTORY' | translate"
            type="submit"
            [disabled]="loading()"
          />
        </form>
        @if (error()) {
          <p role="alert" class="error">{{ error() | translate }}</p>
        }
        <div class="row">
          @for (root of listing()?.roots ?? []; track root) {
            <p-button
              [label]="root"
              icon="pi pi-server"
              severity="secondary"
              (onClick)="load(root)"
              [disabled]="loading()"
            />
          }
        </div>
        @if (listing(); as result) {
          @if (result.parentPath; as parent) {
            <p-button
              [label]="'KZ.PARENT_DIRECTORY' | translate"
              icon="pi pi-arrow-up"
              severity="secondary"
              (onClick)="load(parent)"
              [disabled]="loading()"
            />
          }
          <p class="path">{{ result.currentPath }}</p>
          <div class="directory-list" [attr.aria-busy]="loading()">
            @for (directory of result.directories; track directory.path) {
              <button
                type="button"
                class="choice directory-choice"
                [disabled]="loading()"
                (click)="load(directory.path)"
              >
                <i class="pi pi-folder" aria-hidden="true"></i>{{ directory.name }}
              </button>
            } @empty {
              @if (result.currentPath && !loading()) {
                <p class="hint">{{ 'KZ.NO_SUBDIRECTORIES' | translate }}</p>
              }
            }
          </div>
        }
        <p-button
          [label]="'KZ.SELECT_DIRECTORY' | translate"
          icon="pi pi-check"
          (onClick)="select()"
          [disabled]="loading() || !listing()?.currentPath || !!error()"
        />
      </div>
    </p-dialog>
  `,
  styleUrl: './debrid.scss',
})
export class DirectoryPickerComponent {
  private readonly api = inject(DebridApi);
  @Input() path = '';
  readonly selected = output<string>();
  readonly visible = signal(false);
  readonly loading = signal(false);
  readonly error = signal('');
  readonly listing = signal<StorageDirectories | null>(null);
  location = '';
  private requestId = 0;

  async open(): Promise<void> {
    this.visible.set(true);
    this.listing.set(null);
    await this.load(this.path);
  }

  async load(path: string): Promise<void> {
    const requestId = ++this.requestId;
    this.loading.set(true);
    this.error.set('');
    try {
      const result = await this.api.get<StorageDirectories>(
        '/storage/directories?path=' + encodeURIComponent(path),
      );
      if (requestId !== this.requestId) return;
      this.listing.set(result);
      this.location = result.currentPath ?? '';
    } catch (error) {
      if (requestId === this.requestId)
        this.error.set(
          (error as { error?: { error?: string } })?.error?.error ?? 'KZ.DIRECTORY_UNAVAILABLE',
        );
    } finally {
      if (requestId === this.requestId) this.loading.set(false);
    }
  }

  select(): void {
    const path = this.listing()?.currentPath;
    if (!path || this.loading() || this.error()) return;
    this.selected.emit(path);
    this.visible.set(false);
  }
}
