import { Component, DestroyRef, inject, OnInit, signal } from '@angular/core';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { AppConfirmationService } from '../../core/ui/confirmation.service';
import { Aria2Status, DebridApi, DownloadStatus } from '../../services/debrid-api.service';

/** How often the restart state is re-read while aria2 restarts. */
export const RESTART_POLL_MS = 1000;

/**
 * Restart button for the embedded aria2 and the notice that a startup-only setting is waiting for a restart.
 * The restarting state comes from the backend, so it survives reloads and matches every open client.
 */
@Component({
  selector: 'app-aria2-restart',
  imports: [TranslatePipe, ButtonModule],
  styleUrl: './debrid.scss',
  template: `
    @let current = status();
    <div class="stack aria2-restart">
      @if (current?.restartPending) {
        <p role="status" class="hint restart-pending">
          <i class="pi pi-exclamation-circle" aria-hidden="true"></i>
          {{
            'KZ.ARIA2_RESTART_PENDING'
              | translate
                : {
                    running: current?.runningAutoSaveIntervalSeconds,
                    saved: current?.savedAutoSaveIntervalSeconds,
                  }
          }}
        </p>
      }
      @if (current?.restartState === 'FAILED') {
        <p role="alert" class="error">{{ 'KZ.ARIA2_RESTART_FAILED' | translate }}</p>
      }
      <div class="row">
        <p-button
          [label]="(restarting() ? 'KZ.ARIA2_RESTARTING' : 'KZ.ARIA2_RESTART') | translate"
          icon="pi pi-refresh"
          severity="secondary"
          [loading]="restarting()"
          [disabled]="restarting()"
          (onClick)="restart()"
        />
        <small class="hint">{{ 'KZ.ARIA2_RESTART_HELP' | translate }}</small>
      </div>
    </div>
  `,
})
export class Aria2RestartComponent implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly confirmations = inject(AppConfirmationService);
  private readonly translate = inject(TranslateService);
  private readonly destroy = inject(DestroyRef);
  readonly status = signal<Aria2Status | null>(null);
  /** True from the click until the backend reports that aria2 answers again. */
  readonly restarting = signal(false);
  private poll?: ReturnType<typeof setTimeout>;

  constructor() {
    this.destroy.onDestroy(() => clearTimeout(this.poll));
  }

  async ngOnInit(): Promise<void> {
    await this.refresh();
  }

  async refresh(): Promise<void> {
    clearTimeout(this.poll);
    try {
      this.apply(await this.api.get<Aria2Status>('/aria2/status'));
    } catch {
      // Keep the last known state; the next poll or page load tries again.
      if (this.restarting()) this.schedule();
    }
  }

  async restart(): Promise<void> {
    if (this.restarting()) return;
    if (await this.hasActiveTransfers()) {
      const confirmed = await this.confirmations.confirm({
        message: this.translate.instant('KZ.ARIA2_RESTART_CONFIRM'),
        acceptLabel: this.translate.instant('KZ.ARIA2_RESTART'),
      });
      if (!confirmed) return;
    }
    this.restarting.set(true);
    try {
      this.apply(await this.api.post<Aria2Status>('/aria2/restart'));
    } catch (error) {
      // 409: a restart is already running elsewhere; show it.
      const body = (error as { status?: number; error?: Aria2Status }).error;
      if (body?.restartState) this.apply(body);
      else await this.refresh();
    }
  }

  private apply(status: Aria2Status): void {
    this.status.set(status);
    this.restarting.set(status.restartState === 'RESTARTING');
    if (this.restarting()) this.schedule();
  }

  private schedule(): void {
    if (this.destroy.destroyed) return;
    clearTimeout(this.poll);
    this.poll = setTimeout(() => void this.refresh(), RESTART_POLL_MS);
  }

  private async hasActiveTransfers(): Promise<boolean> {
    try {
      const items = await this.api.get<DownloadStatus[]>('/downloads');
      return items.some((item) => item.request.state === 'TRANSFERRING');
    } catch {
      // When in doubt, ask.
      return true;
    }
  }
}
