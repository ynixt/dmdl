import { TestBed } from '@angular/core/testing';
import { type Confirmation, ConfirmationService } from 'primeng/api';
import { AppConfirmationService } from './confirmation.service';

describe('AppConfirmationService', () => {
  let request: Confirmation | undefined;
  let service: AppConfirmationService;

  beforeEach(() => {
    request = undefined;
    TestBed.configureTestingModule({
      providers: [
        {
          provide: ConfirmationService,
          useValue: {
            confirm: vi.fn((value: Confirmation) => {
              request = value;
            }),
          },
        },
      ],
    });
    service = TestBed.inject(AppConfirmationService);
  });

  it('resolves true once when accepted', async () => {
    const result = service.confirm({ message: 'Continue?' });

    request?.accept?.();
    request?.reject?.();

    await expect(result).resolves.toBe(true);
  });

  it('resolves false when rejected or dismissed', async () => {
    const result = service.confirm({ message: 'Continue?' });

    request?.reject?.();

    await expect(result).resolves.toBe(false);
  });
});
