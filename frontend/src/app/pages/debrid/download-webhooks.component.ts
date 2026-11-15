import { DatePipe } from '@angular/common';
import { Component, computed, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { CheckboxModule } from 'primeng/checkbox';
import { DialogModule } from 'primeng/dialog';
import { DebridApi, Webhook, WebhookDelivery } from '../../services/debrid-api.service';
import { webhookEventLabel } from './webhook-presets';

/** Delivery history of one download plus manual dispatch of webhooks. */
@Component({
  selector: 'app-download-webhooks',
  imports: [DatePipe, FormsModule, TranslatePipe, ButtonModule, CheckboxModule, DialogModule],
  styleUrl: './debrid.scss',
  template: `
    @if (deliveries().length || manualWebhooks().length) {
      <div class="compact-stack notification">
        <div class="row">
          <strong>{{ 'KZ.WEBHOOKS' | translate }}</strong>
          @if (manualWebhooks().length) {
            <p-button
              [label]="'KZ.WEBHOOK_DISPATCH' | translate"
              icon="pi pi-send"
              severity="secondary"
              size="small"
              (onClick)="open()"
              [disabled]="busy()"
            />
          }
        </div>
        @if (deliveries().length) {
          <ul class="event-list" [attr.aria-label]="'KZ.WEBHOOK_DELIVERIES' | translate">
            @for (delivery of deliveries(); track delivery.id) {
              <li class="delivery">
                <span>
                  <strong>{{ delivery.webhookName }}</strong> ·
                  {{
                    (delivery.event ? eventLabel(delivery.event) : 'KZ.WEBHOOK_MANUAL') | translate
                  }}
                  ·
                  <span [class.error]="delivery.state === 'FAILED'">{{
                    (delivery.inProgress
                      ? 'KZ.WEBHOOK_SENDING'
                      : 'KZ.WEBHOOK_STATE_' + delivery.state
                    ) | translate
                  }}</span>
                  @if (delivery.attemptedAt) {
                    · {{ delivery.attemptedAt | date: 'short' }}
                  }
                  @if (delivery.httpStatus) {
                    · HTTP {{ delivery.httpStatus }}
                  }
                </span>
                @if (delivery.state === 'FAILED' && delivery.error) {
                  <span class="error">{{ delivery.error | translate }}</span>
                }
                @if (canResend(delivery)) {
                  <p-button
                    [label]="'KZ.WEBHOOK_RESEND' | translate"
                    icon="pi pi-refresh"
                    severity="secondary"
                    size="small"
                    (onClick)="resend(delivery)"
                    [disabled]="busy()"
                    [loading]="resending() === delivery.id"
                  />
                }
              </li>
            }
          </ul>
        }
        @if (error()) {
          <p role="alert" class="error">{{ error() | translate }}</p>
        }
      </div>
    }
    <p-dialog
      [header]="'KZ.WEBHOOK_DISPATCH' | translate"
      [visible]="visible()"
      (visibleChange)="visible.set($event)"
      [modal]="true"
      [style]="{ width: '32rem', maxWidth: '95vw' }"
    >
      <fieldset class="compact-stack" [disabled]="busy()">
        <legend class="hint">{{ 'KZ.WEBHOOK_DISPATCH_HELP' | translate }}</legend>
        @for (webhook of manualWebhooks(); track webhook.id) {
          <div class="inline-choice">
            <p-checkbox
              [inputId]="'dispatch-' + requestId() + '-' + webhook.id"
              [binary]="true"
              [ngModel]="selected().has(webhook.id!)"
              (ngModelChange)="toggle(webhook.id!, $event)"
            />
            <label [for]="'dispatch-' + requestId() + '-' + webhook.id">{{ webhook.name }}</label>
          </div>
        }
      </fieldset>
      <ng-template #footer>
        <p-button
          [label]="'KZ.CANCEL' | translate"
          severity="secondary"
          (onClick)="visible.set(false)"
          [disabled]="busy()"
        />
        <p-button
          [label]="'KZ.WEBHOOK_DISPATCH_CONFIRM' | translate"
          icon="pi pi-send"
          (onClick)="dispatch()"
          [disabled]="busy() || !selected().size"
          [loading]="busy() && !resending()"
        />
      </ng-template>
    </p-dialog>
  `,
})
export class DownloadWebhooksComponent {
  private readonly api = inject(DebridApi);
  readonly requestId = input.required<string>();
  readonly deliveries = input<WebhookDelivery[]>([]);
  readonly webhooks = input<Webhook[]>([]);
  readonly changed = output<void>();
  readonly manualWebhooks = computed(() =>
    this.webhooks().filter((webhook) => webhook.manual && webhook.enabled && webhook.id),
  );
  readonly visible = signal(false);
  readonly selected = signal<Set<string>>(new Set());
  readonly busy = signal(false);
  readonly resending = signal<string | null>(null);
  readonly error = signal('');
  readonly eventLabel = webhookEventLabel;

  canResend(delivery: WebhookDelivery): boolean {
    return (
      delivery.trigger === 'AUTOMATIC' &&
      delivery.state === 'FAILED' &&
      !delivery.inProgress &&
      !!delivery.webhookId
    );
  }

  open(): void {
    this.selected.set(new Set());
    this.error.set('');
    this.visible.set(true);
  }

  toggle(id: string, checked: boolean): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (checked) next.add(id);
      else next.delete(id);
      return next;
    });
  }

  async dispatch(): Promise<void> {
    const webhookIds = [...this.selected()];
    if (!webhookIds.length) return;
    const sent = await this.run(() =>
      this.api.post(`/downloads/${this.requestId()}/webhooks/dispatch`, { webhookIds }),
    );
    if (sent) this.visible.set(false);
  }

  async resend(delivery: WebhookDelivery): Promise<void> {
    this.resending.set(delivery.id);
    await this.run(() =>
      this.api.post(`/downloads/${this.requestId()}/deliveries/${delivery.id}/resend`),
    );
    this.resending.set(null);
  }

  /** One request at a time per download; a double click cannot queue a second attempt. */
  private async run(action: () => Promise<unknown>): Promise<boolean> {
    if (this.busy()) return false;
    this.busy.set(true);
    this.error.set('');
    try {
      await action();
      this.changed.emit();
      return true;
    } catch (error) {
      this.error.set(
        (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
      );
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
