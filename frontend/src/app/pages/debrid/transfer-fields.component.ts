import { Component, input, output } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { CheckboxModule } from 'primeng/checkbox';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { Settings } from '../../services/debrid-api.service';

@Component({
  selector: 'app-transfer-fields',
  imports: [
    FormsModule,
    TranslatePipe,
    CheckboxModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
  ],
  styleUrl: './debrid.scss',
  template: `
    @let config = settings();
    <div class="stack">
      <div class="form-row">
        <div class="field">
          <label [for]="prefix() + '-concurrency'">{{ 'KZ.CONCURRENCY' | translate }}</label>
          <input
            pInputText
            [id]="prefix() + '-concurrency'"
            type="number"
            min="1"
            max="32"
            [ngModel]="config.maxConcurrentDownloads"
            (ngModelChange)="changeConcurrency($event)"
          />
        </div>
        @if (config.maxConcurrentDownloads > 1) {
          <div class="field">
            <label [for]="prefix() + '-debrid-concurrency'">{{
              'KZ.DEBRID_CONCURRENCY' | translate
            }}</label>
            <input
              pInputText
              [id]="prefix() + '-debrid-concurrency'"
              type="number"
              min="1"
              [max]="config.maxConcurrentDownloads"
              [ngModel]="config.transfer.maxConcurrentPerDebrid ?? 1"
              (ngModelChange)="config.transfer.maxConcurrentPerDebrid = $event; changed.emit()"
            />
          </div>
        }
      </div>
      @if (config.maxConcurrentDownloads > 1) {
        <p role="note" class="hint">{{ 'KZ.DEBRID_CONCURRENCY_WARNING' | translate }}</p>
      }
      <div class="form-row top-aligned">
        <div class="field">
          <label [for]="prefix() + '-connections'">{{ 'KZ.CONNECTIONS' | translate }}</label>
          <input
            pInputText
            [id]="prefix() + '-connections'"
            type="number"
            min="1"
            max="16"
            [ngModel]="config.transfer.maxConnectionPerServer"
            (ngModelChange)="changeConnections($event)"
          />
          <small class="hint">{{ 'KZ.CONNECTIONS_HELP' | translate }}</small>
        </div>
        <div class="field">
          <label [for]="prefix() + '-min-split'">{{ 'KZ.MIN_SPLIT' | translate }}</label>
          <input
            pInputText
            [id]="prefix() + '-min-split'"
            type="number"
            min="1"
            max="1024"
            [(ngModel)]="config.transfer.minSplitSizeMib"
            (ngModelChange)="changed.emit()"
          />
        </div>
      </div>
      <div class="field">
        <div class="row">
          <p-checkbox
            [disabled]="disabled()"
            [inputId]="prefix() + '-lowest-speed-enabled'"
            [binary]="true"
            [ngModel]="config.transfer.lowestSpeedLimitEnabled ?? false"
            (ngModelChange)="config.transfer.lowestSpeedLimitEnabled = $event; changed.emit()"
          />
          <label [for]="prefix() + '-lowest-speed-enabled'">{{
            'KZ.LOWEST_SPEED_ENABLED' | translate
          }}</label>
        </div>
        <small class="hint">{{ 'KZ.LOWEST_SPEED_ENABLED_HINT' | translate }}</small>
      </div>
      <div class="form-row">
        @if (config.transfer.lowestSpeedLimitEnabled) {
          <div class="field">
            <label [for]="prefix() + '-lowest-speed'"
              >{{ 'KZ.LOWEST_SPEED' | translate }} (KiB/s)</label
            >
            <input
              pInputText
              [id]="prefix() + '-lowest-speed'"
              type="number"
              min="1"
              max="1048576"
              [ngModel]="config.transfer.lowestSpeedLimitKib ?? 50"
              (ngModelChange)="config.transfer.lowestSpeedLimitKib = $event; changed.emit()"
            />
          </div>
        }
        <div class="field">
          <label [for]="prefix() + '-disk-cache'">{{ 'KZ.DISK_CACHE' | translate }} (MiB)</label>
          <input
            pInputText
            [id]="prefix() + '-disk-cache'"
            type="number"
            min="0"
            max="4096"
            [ngModel]="config.transfer.diskCacheMib ?? 64"
            (ngModelChange)="config.transfer.diskCacheMib = $event; changed.emit()"
          />
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label [for]="prefix() + '-timeout'">{{ 'KZ.TIMEOUT' | translate }} (s)</label>
          <input
            pInputText
            [id]="prefix() + '-timeout'"
            type="number"
            min="1"
            max="600"
            [ngModel]="config.transfer.timeoutSeconds ?? 60"
            (ngModelChange)="config.transfer.timeoutSeconds = $event; changed.emit()"
          />
        </div>
        <div class="field">
          <label [for]="prefix() + '-tries'">{{ 'KZ.MAX_TRIES' | translate }}</label>
          <input
            pInputText
            [id]="prefix() + '-tries'"
            type="number"
            min="0"
            max="100"
            [(ngModel)]="config.transfer.maxTries"
            (ngModelChange)="changed.emit()"
          />
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label [for]="prefix() + '-retry-wait'">{{ 'KZ.RETRY_WAIT' | translate }}</label>
          <input
            pInputText
            [id]="prefix() + '-retry-wait'"
            type="number"
            min="0"
            max="600"
            [(ngModel)]="config.transfer.retryWaitSeconds"
            (ngModelChange)="changed.emit()"
          />
        </div>
        <div class="field">
          <label [for]="prefix() + '-file-not-found'">{{
            'KZ.MAX_FILE_NOT_FOUND' | translate
          }}</label>
          <input
            pInputText
            [id]="prefix() + '-file-not-found'"
            type="number"
            min="0"
            max="100"
            [ngModel]="config.transfer.maxFileNotFound ?? 2"
            (ngModelChange)="config.transfer.maxFileNotFound = $event; changed.emit()"
          />
        </div>
      </div>
      <div class="form-row">
        <div class="field">
          <label [for]="prefix() + '-allocation'">{{ 'KZ.ALLOCATION' | translate }}</label>
          <p-select
            [disabled]="disabled()"
            [inputId]="prefix() + '-allocation'"
            [options]="allocations"
            [(ngModel)]="config.transfer.fileAllocation"
            (ngModelChange)="changed.emit()"
            appendTo="body"
          />
        </div>
        <div class="field">
          <label [for]="prefix() + '-ipv6'">IPv6</label>
          <p-toggleswitch
            [disabled]="disabled()"
            [inputId]="prefix() + '-ipv6'"
            [(ngModel)]="config.transfer.ipv6Enabled"
            (ngModelChange)="changed.emit()"
          />
        </div>
      </div>
    </div>
  `,
})
export class TransferFieldsComponent {
  readonly disabled = input(false);
  readonly changed = output<void>();
  readonly settings = input.required<Pick<Settings, 'maxConcurrentDownloads' | 'transfer'>>();
  readonly prefix = input('transfer');
  readonly allocations = ['NONE', 'PREALLOC', 'TRUNC', 'FALLOC'];

  changeConnections(connections: number): void {
    this.settings().transfer.maxConnectionPerServer = connections;
    this.settings().transfer.split = connections;
    this.changed.emit();
  }

  changeConcurrency(concurrency: number): void {
    this.settings().maxConcurrentDownloads = concurrency;
    this.settings().transfer.maxConcurrentPerDebrid = Math.min(
      this.settings().transfer.maxConcurrentPerDebrid ?? 1,
      concurrency,
    );
    this.changed.emit();
  }
}
