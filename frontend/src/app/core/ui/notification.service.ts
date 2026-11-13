import { inject, Injectable } from '@angular/core';
import { MessageService } from 'primeng/api';

export type NotificationSeverity = 'success' | 'info' | 'warn' | 'error';

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private readonly messages = inject(MessageService);

  success(detail: string, life = 3000): void {
    this.show('success', detail, life);
  }

  info(detail: string, life = 3000): void {
    this.show('info', detail, life);
  }

  warning(detail: string, life = 4000): void {
    this.show('warn', detail, life);
  }

  error(detail: string, life = 4500): void {
    this.show('error', detail, life);
  }

  private show(severity: NotificationSeverity, detail: string, life: number): void {
    this.messages.add({ severity, detail, life });
  }
}
