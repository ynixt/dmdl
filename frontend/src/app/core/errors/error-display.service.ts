import { HttpErrorResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

@Injectable({ providedIn: 'root' })
export class ErrorDisplayService {
  private readonly translate = inject(TranslateService);

  extractMessage(
    error: unknown,
    fallbackKey = 'GENERAL_CONFIG.UNEXPECTED_ERROR',
    fallbackMessage?: string,
  ): string {
    if (this.isHttpErrorResponse(error)) {
      const backendMessage =
        (error.error as { message?: string } | null)?.message ??
        (error.error as { error?: string } | null)?.error;
      if (backendMessage) return backendMessage;

      if (error.message) return error.message;
    } else if (error && typeof error === 'object' && 'message' in error) {
      const message = (error as { message?: unknown }).message;
      if (typeof message === 'string' && message.trim()) return message;
    }

    return fallbackMessage || this.translate.instant(fallbackKey);
  }

  private isHttpErrorResponse(error: unknown): error is HttpErrorResponse {
    return error instanceof HttpErrorResponse;
  }
}
