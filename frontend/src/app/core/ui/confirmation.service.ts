import { inject, Injectable } from '@angular/core';
import { Confirmation, ConfirmationService } from 'primeng/api';

export interface ConfirmationOptions {
  message: string;
  header?: string;
  acceptLabel?: string;
  rejectLabel?: string;
  acceptIcon?: string;
  rejectIcon?: string;
  danger?: boolean;
  dismissible?: boolean;
}

@Injectable({ providedIn: 'root' })
export class AppConfirmationService {
  private readonly confirmations = inject(ConfirmationService);

  confirm(options: ConfirmationOptions): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      let settled = false;
      const settle = (result: boolean): void => {
        if (settled) return;
        settled = true;
        // PrimeNG 21 can report visibleChange(false) after an action has closed the dialog.
        // Release its event references so that second close cannot emit into a closed subject.
        confirmation.acceptEvent?.unsubscribe();
        confirmation.rejectEvent?.unsubscribe();
        confirmation.acceptEvent = undefined;
        confirmation.rejectEvent = undefined;
        resolve(result);
      };

      const confirmation: Confirmation = {
        message: options.message,
        header: options.header,
        icon: 'pi pi-exclamation-triangle',
        acceptLabel: options.acceptLabel,
        rejectLabel: options.rejectLabel,
        acceptIcon: options.acceptIcon ?? 'pi pi-check',
        rejectIcon: options.rejectIcon ?? 'pi pi-times',
        acceptButtonStyleClass: options.danger ? 'p-button-danger' : undefined,
        rejectButtonStyleClass: 'p-button-text',
        defaultFocus: options.danger ? 'reject' : 'accept',
        closable: options.dismissible ?? true,
        closeOnEscape: options.dismissible ?? true,
        dismissableMask: options.dismissible ?? true,
        accept: () => settle(true),
        reject: () => settle(false),
      };
      this.confirmations.confirm(confirmation);
    });
  }
}
