import { Component } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { Subject } from 'rxjs';
import { DialogService } from 'primeng/dynamicdialog';
import { AppDialogService } from './dialog.service';

@Component({ template: '' })
class TestDialogComponent {}

describe('AppDialogService', () => {
  it('returns the first typed close result and configures a modal dialog', async () => {
    const onClose = new Subject<{ saved: boolean } | undefined>();
    const onDestroy = new Subject<void>();
    const dialogs = {
      open: vi.fn().mockReturnValue({ onClose, onDestroy }),
    };
    TestBed.configureTestingModule({
      providers: [{ provide: DialogService, useValue: dialogs }],
    });
    const service = TestBed.inject(AppDialogService);

    const resultPromise = service.open<{ id: string }, { saved: boolean }>(
      TestDialogComponent,
      { id: 'media-1' },
      { header: 'Edit', dismissible: false },
    );
    onClose.next({ saved: true });

    await expect(resultPromise).resolves.toEqual({ saved: true });
    expect(dialogs.open).toHaveBeenCalledWith(
      TestDialogComponent,
      expect.objectContaining({
        data: { id: 'media-1' },
        header: 'Edit',
        modal: true,
        closable: false,
        closeOnEscape: false,
        dismissableMask: false,
        focusTrap: true,
      }),
    );
  });

  it('resolves dismissal without producing a result', async () => {
    const onClose = new Subject<unknown>();
    const onDestroy = new Subject<void>();
    TestBed.configureTestingModule({
      providers: [
        {
          provide: DialogService,
          useValue: { open: vi.fn().mockReturnValue({ onClose, onDestroy }) },
        },
      ],
    });
    const service = TestBed.inject(AppDialogService);

    const resultPromise = service.open(TestDialogComponent, {});
    onDestroy.next();

    await expect(resultPromise).resolves.toBeUndefined();
  });
});
