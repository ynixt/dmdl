import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, Webhook, WebhookDelivery } from '../../services/debrid-api.service';
import { DownloadWebhooksComponent } from './download-webhooks.component';

describe('Download webhook deliveries and manual dispatch', () => {
  const api = { post: vi.fn() };
  let fixture: ComponentFixture<DownloadWebhooksComponent>;
  const hook = (id: string, manual = true, enabled = true): Webhook => ({
    id,
    name: id,
    method: 'POST',
    url: `https://${id}.local`,
    headers: [],
    bodyMode: 'NONE',
    events: [],
    manual,
    enabled,
  });
  const delivery = (id: string, patch: Partial<WebhookDelivery>): WebhookDelivery => ({
    id,
    requestId: 'download',
    webhookId: 'ha',
    webhookName: 'Home Assistant',
    trigger: 'AUTOMATIC',
    event: 'download.completed',
    state: 'SENT',
    inProgress: false,
    attempts: 1,
    createdAt: '2026-11-15T00:00:00Z',
    ...patch,
  });

  beforeEach(() => {
    vi.resetAllMocks();
    TestBed.configureTestingModule({
      imports: [DownloadWebhooksComponent],
      providers: [provideTranslateService(), { provide: DebridApi, useValue: api }],
    });
    fixture = TestBed.createComponent(DownloadWebhooksComponent);
    fixture.componentRef.setInput('requestId', 'download');
  });

  it('offers only enabled manual webhooks and hides itself when there is nothing to show', () => {
    fixture.componentRef.setInput('webhooks', [hook('auto', false), hook('off', true, false)]);
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent.trim()).toBe('');
    fixture.componentRef.setInput('webhooks', [
      hook('ha'),
      hook('auto', false),
      hook('off', true, false),
    ]);
    fixture.detectChanges();
    expect(fixture.componentInstance.manualWebhooks().map((webhook) => webhook.id)).toEqual(['ha']);
    expect(fixture.nativeElement.textContent).toContain('KZ.WEBHOOK_DISPATCH');
  });

  it('dispatches the selected webhooks once even when activated twice', async () => {
    fixture.componentRef.setInput('webhooks', [hook('ha'), hook('ntfy')]);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    const changed = vi.fn();
    component.changed.subscribe(changed);
    component.open();
    component.toggle('ha', true);
    component.toggle('ntfy', true);
    component.toggle('ntfy', false);
    let finish!: () => void;
    api.post.mockImplementationOnce(() => new Promise<void>((resolve) => (finish = resolve)));
    const first = component.dispatch();
    const second = component.dispatch();
    finish();
    await Promise.all([first, second]);
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/downloads/download/webhooks/dispatch', {
      webhookIds: ['ha'],
    });
    expect(changed).toHaveBeenCalledTimes(1);
    expect(component.visible()).toBe(false);
  });

  it('resends only failed automatic deliveries of an existing webhook and reports errors', async () => {
    const failed = delivery('failed', {
      state: 'FAILED',
      error: 'KZ.WEBHOOK_HTTP_STATUS',
      httpStatus: 503,
    });
    const deliveries = [
      failed,
      delivery('manual', { state: 'FAILED', trigger: 'MANUAL', event: null }),
      delivery('orphan', { state: 'FAILED', webhookId: null }),
      delivery('sending', { state: 'PENDING', inProgress: true }),
      delivery('sent', {}),
    ];
    fixture.componentRef.setInput('deliveries', deliveries);
    fixture.detectChanges();
    const component = fixture.componentInstance;
    expect(deliveries.filter((item) => component.canResend(item)).map((item) => item.id)).toEqual([
      'failed',
    ]);
    const text = fixture.nativeElement.textContent;
    expect(text).toContain('KZ.WEBHOOK_EVENT_COMPLETED');
    expect(text).toContain('KZ.WEBHOOK_MANUAL');
    expect(text).toContain('KZ.WEBHOOK_SENDING');
    expect(text).toContain('HTTP 503');
    expect(text).toContain('KZ.WEBHOOK_HTTP_STATUS');

    api.post.mockRejectedValueOnce({ error: { error: 'KZ.WEBHOOK_DELIVERY_ACTIVE' } });
    await component.resend(failed);
    expect(api.post).toHaveBeenCalledExactlyOnceWith(
      '/downloads/download/deliveries/failed/resend',
    );
    expect(component.error()).toBe('KZ.WEBHOOK_DELIVERY_ACTIVE');
    expect(component.resending()).toBeNull();
  });
});
