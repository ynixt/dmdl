import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from '@openng/optimus-ui/button';
import { CheckboxModule } from '@openng/optimus-ui/checkbox';
import { InputTextModule } from '@openng/optimus-ui/inputtext';
import { SelectModule } from '@openng/optimus-ui/select';
import { ToggleSwitchModule } from '@openng/optimus-ui/toggleswitch';
import {
  CodeEditorComponent,
  CodeEditorLanguage,
} from '../../components/code-editor/code-editor.component';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import {
  DebridApi,
  Webhook,
  WebhookEvent,
  WebhookPreview,
  WebhookTestResult,
} from '../../services/debrid-api.service';
import { SettingsDraftStore } from './settings-draft.store';
import { SettingsFeedbackComponent } from './settings-feedback.component';
import {
  WEBHOOK_EVENTS,
  WEBHOOK_METHODS,
  WEBHOOK_VARIABLES,
  webhookEventLabel,
  webhookPreset,
  WebhookPresetId,
} from './webhook-presets';

/** Simulated trigger for previews and tests; '' is a manual dispatch. */
type Simulation = WebhookEvent | '';

@Component({
  selector: 'app-settings-webhooks-tab',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    CheckboxModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
    SecretInputComponent,
    CodeEditorComponent,
    SettingsFeedbackComponent,
  ],
  styleUrls: ['./debrid.scss', './wizard.scss'],
  template: `
    <app-settings-feedback [error]="slice.error()" [message]="slice.message()" />
    <fieldset class="wizard-fields" [disabled]="slice.saving()" [attr.aria-busy]="slice.saving()">
      <p>{{ 'KZ.WEBHOOKS_HELP' | translate }}</p>
      <div class="row">
        <p-select
          inputId="webhook-preset"
          [options]="presets"
          optionLabel="label"
          optionValue="id"
          [ngModel]="preset()"
          (ngModelChange)="preset.set($event)"
          [ariaLabel]="'KZ.WEBHOOK_PRESET' | translate"
        >
          <ng-template #selectedItem let-option>{{ option.label | translate }}</ng-template>
          <ng-template #item let-option>{{ option.label | translate }}</ng-template>
        </p-select>
        <p-button
          [label]="'KZ.WEBHOOK_ADD' | translate"
          icon="pi pi-plus"
          severity="secondary"
          (onClick)="add()"
        />
      </div>
      @if (!items().length) {
        <p class="hint">{{ 'KZ.WEBHOOKS_EMPTY' | translate }}</p>
      }
      @for (webhook of items(); track webhook.id; let index = $index) {
        <section
          class="panel compact-stack webhook"
          [attr.aria-label]="webhook.name || ('KZ.WEBHOOK' | translate)"
        >
          <div class="row">
            <h3>{{ webhook.name || ('KZ.WEBHOOK' | translate) }}</h3>
            <p-button
              icon="pi pi-trash"
              severity="danger"
              [text]="true"
              [ariaLabel]="('KZ.WEBHOOK_REMOVE' | translate) + ' ' + webhook.name"
              (onClick)="remove(index)"
            />
          </div>
          <div class="field">
            <label [for]="field(index, 'name')">{{ 'KZ.WEBHOOK_NAME' | translate }}</label>
            <input
              pInputText
              [id]="field(index, 'name')"
              [ngModel]="webhook.name"
              (ngModelChange)="update(index, { name: $event })"
              [attr.aria-invalid]="!!error(index, 'name')"
              [attr.aria-describedby]="field(index, 'name') + '-error'"
            />
            @if (error(index, 'name'); as message) {
              <p [id]="field(index, 'name') + '-error'" role="alert" class="error">
                {{ message | translate }}
              </p>
            }
          </div>
          <div class="row">
            <div class="inline-choice">
              <p-toggleswitch
                [inputId]="field(index, 'enabled')"
                [ngModel]="webhook.enabled"
                (ngModelChange)="update(index, { enabled: $event })"
              />
              <label [for]="field(index, 'enabled')">{{ 'KZ.ENABLED' | translate }}</label>
            </div>
            <div class="inline-choice">
              <p-toggleswitch
                [inputId]="field(index, 'manual')"
                [ngModel]="webhook.manual"
                (ngModelChange)="update(index, { manual: $event })"
              />
              <label [for]="field(index, 'manual')">{{
                'KZ.WEBHOOK_MANUAL_AVAILABLE' | translate
              }}</label>
            </div>
          </div>
          <fieldset
            class="compact-stack"
            [attr.aria-describedby]="field(index, 'events') + '-error'"
          >
            <legend>{{ 'KZ.WEBHOOK_EVENTS' | translate }}</legend>
            <div class="row">
              @for (event of events; track event) {
                <div class="inline-choice">
                  <p-checkbox
                    [inputId]="field(index, event)"
                    [binary]="true"
                    [ngModel]="webhook.events.includes(event)"
                    (ngModelChange)="toggleEvent(index, event, $event)"
                  />
                  <label [for]="field(index, event)">{{ eventLabel(event) | translate }}</label>
                </div>
              }
            </div>
            @if (error(index, 'events'); as message) {
              <p [id]="field(index, 'events') + '-error'" role="alert" class="error">
                {{ message | translate }}
              </p>
            }
          </fieldset>
          <div class="row">
            <div class="field">
              <label [for]="field(index, 'method')">{{ 'KZ.WEBHOOK_METHOD' | translate }}</label>
              <p-select
                [inputId]="field(index, 'method')"
                [options]="methods"
                [ngModel]="webhook.method"
                (ngModelChange)="update(index, { method: $event })"
              />
            </div>
            <div class="field grow">
              <label [for]="field(index, 'url')">{{ 'KZ.WEBHOOK_URL' | translate }}</label>
              <app-secret-input
                [inputId]="field(index, 'url')"
                kind="secret"
                [revealed]="isNew(webhook)"
                [ngModel]="webhook.url"
                (ngModelChange)="update(index, { url: $event ?? '' })"
                [invalid]="!!error(index, 'url')"
                [describedBy]="field(index, 'url') + '-error'"
              />
              @if (error(index, 'url'); as message) {
                <p [id]="field(index, 'url') + '-error'" role="alert" class="error">
                  {{ message | translate }}
                </p>
              }
            </div>
          </div>
          <fieldset class="compact-stack">
            <legend>{{ 'KZ.WEBHOOK_HEADERS' | translate }}</legend>
            @for (header of webhook.headers; track $index; let headerIndex = $index) {
              <div class="row">
                <div class="field">
                  <label [for]="field(index, 'headers.' + headerIndex + '.name')">{{
                    'KZ.WEBHOOK_HEADER_NAME' | translate
                  }}</label>
                  <input
                    pInputText
                    [id]="field(index, 'headers.' + headerIndex + '.name')"
                    [ngModel]="header.name"
                    (ngModelChange)="updateHeader(index, headerIndex, { name: $event })"
                    [attr.aria-invalid]="!!error(index, 'headers.' + headerIndex + '.name')"
                  />
                  @if (error(index, 'headers.' + headerIndex + '.name'); as message) {
                    <p role="alert" class="error">{{ message | translate }}</p>
                  }
                </div>
                <div class="field grow">
                  <label [for]="field(index, 'headers.' + headerIndex + '.value')">{{
                    'KZ.WEBHOOK_HEADER_VALUE' | translate
                  }}</label>
                  <app-secret-input
                    [inputId]="field(index, 'headers.' + headerIndex + '.value')"
                    kind="secret"
                    [revealed]="isNew(webhook) || !header.value"
                    [ngModel]="header.value"
                    (ngModelChange)="updateHeader(index, headerIndex, { value: $event ?? '' })"
                    [invalid]="!!error(index, 'headers.' + headerIndex + '.value')"
                  />
                  @if (error(index, 'headers.' + headerIndex + '.value'); as message) {
                    <p role="alert" class="error">{{ message | translate }}</p>
                  }
                </div>
                <p-button
                  icon="pi pi-times"
                  severity="secondary"
                  [text]="true"
                  [ariaLabel]="'KZ.WEBHOOK_HEADER_REMOVE' | translate"
                  (onClick)="removeHeader(index, headerIndex)"
                />
              </div>
            }
            <p-button
              [label]="'KZ.WEBHOOK_HEADER_ADD' | translate"
              icon="pi pi-plus"
              severity="secondary"
              size="small"
              (onClick)="addHeader(index)"
            />
          </fieldset>
          <div class="field">
            <label [for]="field(index, 'bodyMode')">{{ 'KZ.WEBHOOK_BODY_MODE' | translate }}</label>
            <p-select
              [inputId]="field(index, 'bodyMode')"
              [options]="bodyModes"
              optionLabel="label"
              optionValue="id"
              [ngModel]="webhook.bodyMode"
              (ngModelChange)="update(index, { bodyMode: $event })"
            >
              <ng-template #selectedItem let-option>{{ option.label | translate }}</ng-template>
              <ng-template #item let-option>{{ option.label | translate }}</ng-template>
            </p-select>
          </div>
          @if (webhook.bodyMode === 'TEMPLATE') {
            <div class="field">
              <!-- The editor is not a native control: the label names it through aria-labelledby. -->
              <span [id]="field(index, 'body') + '-label'">{{
                'KZ.WEBHOOK_BODY' | translate
              }}</span>
              <app-code-editor
                [inputId]="field(index, 'body')"
                [labelledBy]="field(index, 'body') + '-label'"
                [describedBy]="bodyDescription(index, webhook)"
                [language]="bodyLanguage(webhook)"
                [invalid]="!!error(index, 'body')"
                [ngModel]="webhook.body"
                (ngModelChange)="update(index, { body: $event })"
              />
              @if (error(index, 'body'); as message) {
                <p role="alert" class="error">{{ message | translate }}</p>
              }
              @if (jsonWithoutContentType(webhook)) {
                <p class="hint" [id]="field(index, 'body-json')">
                  {{ 'KZ.WEBHOOK_BODY_JSON_HINT' | translate }}
                </p>
              }
            </div>
          }
          <p class="hint" [id]="field(index, 'variables')">
            {{ 'KZ.WEBHOOK_VARIABLES' | translate }}
            @for (variable of variables; track variable) {
              <code>{{ token(variable) }}</code>
            }
          </p>
          <div class="row">
            <div class="field">
              <label [for]="field(index, 'simulate')">{{
                'KZ.WEBHOOK_SIMULATE' | translate
              }}</label>
              <p-select
                [inputId]="field(index, 'simulate')"
                [options]="simulations"
                optionLabel="label"
                optionValue="id"
                [ngModel]="simulation(webhook)"
                (ngModelChange)="simulate(webhook, $event)"
              >
                <ng-template #selectedItem let-option>{{ option.label | translate }}</ng-template>
                <ng-template #item let-option>{{ option.label | translate }}</ng-template>
              </p-select>
            </div>
            <p-button
              [label]="'KZ.WEBHOOK_PREVIEW' | translate"
              icon="pi pi-eye"
              severity="secondary"
              (onClick)="preview(webhook)"
              [loading]="pending() === webhook.id + ':preview'"
              [disabled]="!!pending()"
            />
            <p-button
              [label]="'KZ.TEST_CONNECTION' | translate"
              icon="pi pi-check-circle"
              severity="secondary"
              (onClick)="test(webhook)"
              [loading]="pending() === webhook.id + ':test'"
              [disabled]="!!pending()"
            />
          </div>
          @if (results()[webhook.id!]; as result) {
            <div [attr.role]="result.ok ? 'status' : 'alert'" class="compact-stack">
              @if (result.message) {
                <p [class.error]="!result.ok" [class.hint]="result.ok">
                  {{ result.message | translate }}
                  @if (result.status) {
                    · HTTP {{ result.status }}
                  }
                </p>
              }
              @if (result.preview; as rendered) {
                <pre class="webhook-preview">{{ previewText(rendered) }}</pre>
              }
            </div>
          }
        </section>
      }
    </fieldset>
    <footer class="wizard-footer">
      <p-button
        [label]="'KZ.SAVE_SETTINGS' | translate"
        icon="pi pi-save"
        [disabled]="!slice.dirty() || slice.saving()"
        [loading]="slice.saving()"
        (onClick)="store.saveWebhooks()"
      />
    </footer>
  `,
})
export class SettingsWebhooksTabComponent {
  readonly store = inject(SettingsDraftStore);
  readonly slice = this.store.webhooks;
  private readonly api = inject(DebridApi);
  readonly events = WEBHOOK_EVENTS;
  readonly methods = [...WEBHOOK_METHODS];
  readonly variables = WEBHOOK_VARIABLES;
  readonly presets: { id: WebhookPresetId; label: string }[] = [
    { id: 'blank', label: 'KZ.WEBHOOK_PRESET_BLANK' },
    { id: 'jellyfin', label: 'KZ.WEBHOOK_PRESET_JELLYFIN' },
    { id: 'homeAssistant', label: 'KZ.WEBHOOK_PRESET_HOME_ASSISTANT' },
    { id: 'ntfy', label: 'KZ.WEBHOOK_PRESET_NTFY' },
  ];
  readonly bodyModes = [
    { id: 'NONE', label: 'KZ.WEBHOOK_BODY_NONE' },
    { id: 'TEMPLATE', label: 'KZ.WEBHOOK_BODY_TEMPLATE' },
  ];
  readonly simulations: { id: Simulation; label: string }[] = [
    { id: '', label: 'KZ.WEBHOOK_MANUAL' },
    ...WEBHOOK_EVENTS.map((event) => ({ id: event, label: webhookEventLabel(event) })),
  ];
  readonly preset = signal<WebhookPresetId>('blank');
  readonly pending = signal<string | null>(null);
  readonly results = signal<
    Record<
      string,
      { ok: boolean; message?: string; status?: number | null; preview?: WebhookPreview }
    >
  >({});
  private readonly simulated = signal<Record<string, Simulation>>({});

  readonly eventLabel = webhookEventLabel;

  token(variable: string): string {
    return `{{${variable}}}`;
  }

  previewText(rendered: WebhookPreview): string {
    return [
      `${rendered.method} ${rendered.url}`,
      ...rendered.headers.map((header) => `${header.name}: ${header.value}`),
      '',
      rendered.body ?? '',
    ].join('\n');
  }

  items(): Webhook[] {
    return this.slice.draft().items;
  }

  field(index: number, name: string): string {
    return `webhook-${index}-${name.replace(/\./g, '-')}`;
  }

  error(index: number, name: string): string | undefined {
    return this.slice.errors()[`webhooks.${index}.${name}`];
  }

  /** Webhooks added since the tab loaded; their URL and header values start visible. */
  private readonly created = new Set<string>();

  /** Same rule as the backend, which JSON-escapes values only for a JSON Content-Type. */
  private jsonContentType(webhook: Webhook): boolean {
    const contentType = webhook.headers
      .filter((header) => header.name.trim().toLowerCase() === 'content-type')
      .at(-1);
    return /application\/json|\+json/i.test(contentType?.value ?? '');
  }

  /** Starts with an object or array, not with a Mustache tag. */
  private bodyLooksLikeJson(webhook: Webhook): boolean {
    return /^\s*(\[|\{(?!\{))/.test(webhook.body ?? '');
  }

  /** Highlighting is only visual, so a JSON-looking body is highlighted as JSON even without the header. */
  bodyLanguage(webhook: Webhook): CodeEditorLanguage {
    return this.jsonContentType(webhook) || this.bodyLooksLikeJson(webhook) ? 'json' : 'text';
  }

  jsonWithoutContentType(webhook: Webhook): boolean {
    return this.bodyLooksLikeJson(webhook) && !this.jsonContentType(webhook);
  }

  bodyDescription(index: number, webhook: Webhook): string {
    const ids = [this.field(index, 'variables')];
    if (this.jsonWithoutContentType(webhook)) {
      ids.push(this.field(index, 'body-json'));
    }
    return ids.join(' ');
  }

  isNew(webhook: Webhook): boolean {
    return webhook.id !== undefined && this.created.has(webhook.id);
  }

  add(): void {
    const webhook = webhookPreset(this.preset());
    if (webhook.id) {
      this.created.add(webhook.id);
    }
    this.slice.patch({ items: [...this.items(), webhook] });
  }

  remove(index: number): void {
    this.slice.patch({ items: this.items().filter((_, position) => position !== index) });
  }

  update(index: number, value: Partial<Webhook>): void {
    this.slice.patch({
      items: this.items().map((webhook, position) =>
        position === index ? { ...webhook, ...value } : webhook,
      ),
    });
  }

  toggleEvent(index: number, event: WebhookEvent, checked: boolean): void {
    const current = this.items()[index].events.filter((item) => item !== event);
    // Keep the canonical event order regardless of click order.
    const events = WEBHOOK_EVENTS.filter((item) =>
      item === event ? checked : current.includes(item),
    );
    this.update(index, { events });
  }

  addHeader(index: number): void {
    this.update(index, { headers: [...this.items()[index].headers, { name: '', value: '' }] });
  }

  removeHeader(index: number, headerIndex: number): void {
    this.update(index, {
      headers: this.items()[index].headers.filter((_, position) => position !== headerIndex),
    });
  }

  updateHeader(
    index: number,
    headerIndex: number,
    value: Partial<{ name: string; value: string }>,
  ): void {
    this.update(index, {
      headers: this.items()[index].headers.map((header, position) =>
        position === headerIndex ? { ...header, ...value } : header,
      ),
    });
  }

  simulation(webhook: Webhook): Simulation {
    return this.simulated()[webhook.id!] ?? webhook.events[0] ?? '';
  }

  simulate(webhook: Webhook, value: Simulation): void {
    this.simulated.update((current) => ({ ...current, [webhook.id!]: value }));
  }

  /** Server-rendered, so the preview always matches what is sent. */
  preview(webhook: Webhook): Promise<void> {
    return this.call(webhook, 'preview', async (body) => {
      const rendered = await this.api.post<WebhookPreview>('/webhooks/preview', body);
      return { ok: true, preview: rendered };
    });
  }

  /** Sends one real request with the values being edited, saved or not. */
  test(webhook: Webhook): Promise<void> {
    return this.call(webhook, 'test', async (body) => {
      const result = await this.api.post<WebhookTestResult>('/webhooks/test', body);
      return {
        ok: result.ok,
        status: result.status,
        message: result.ok ? 'KZ.CONNECTION_OK' : (result.error ?? 'KZ.REQUEST_FAILED'),
      };
    });
  }

  private async call(
    webhook: Webhook,
    kind: 'preview' | 'test',
    action: (body: unknown) => Promise<{
      ok: boolean;
      message?: string;
      status?: number | null;
      preview?: WebhookPreview;
    }>,
  ): Promise<void> {
    if (this.pending()) return;
    const id = webhook.id!;
    this.pending.set(`${id}:${kind}`);
    const event = this.simulation(webhook);
    try {
      const result = await action({
        webhook: structuredClone(webhook),
        ...(event ? { event } : {}),
      });
      this.results.update((current) => ({ ...current, [id]: result }));
    } catch (error) {
      const response = (error as { error?: { error?: string; errors?: Record<string, string> } })
        ?.error;
      const fieldError = Object.values(response?.errors ?? {})[0];
      this.results.update((current) => ({
        ...current,
        [id]: { ok: false, message: fieldError ?? response?.error ?? 'KZ.REQUEST_FAILED' },
      }));
    } finally {
      this.pending.set(null);
    }
  }
}
