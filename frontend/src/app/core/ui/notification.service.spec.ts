import { TestBed } from '@angular/core/testing';
import { MessageService } from '@openng/optimus-ui/api';
import { NotificationService } from './notification.service';

describe('NotificationService', () => {
  const messageService = { add: vi.fn() };
  let service: NotificationService;

  beforeEach(() => {
    messageService.add.mockReset();
    TestBed.configureTestingModule({
      providers: [{ provide: MessageService, useValue: messageService }],
    });
    service = TestBed.inject(NotificationService);
  });

  it.each([
    ['success', 'success', 3000],
    ['info', 'info', 3000],
    ['warning', 'warn', 4000],
    ['error', 'error', 4500],
  ] as const)('publishes a %s toast', (method, severity, life) => {
    service[method]('Translated message');

    expect(messageService.add).toHaveBeenCalledWith({
      severity,
      detail: 'Translated message',
      life,
    });
  });

  it('accepts a feature-specific lifetime', () => {
    service.error('Still failed', 7000);

    expect(messageService.add).toHaveBeenCalledWith({
      severity: 'error',
      detail: 'Still failed',
      life: 7000,
    });
  });
});
