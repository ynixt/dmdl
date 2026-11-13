import { Injectable, Type, inject } from '@angular/core';
import { firstValueFrom, map, merge, take } from 'rxjs';
import { DialogService, DynamicDialogConfig, DynamicDialogRef } from 'primeng/dynamicdialog';

export interface AppDialogOptions {
  header?: string;
  width?: string;
  maxWidth?: string;
  dismissible?: boolean;
  focusOnShow?: boolean;
  styleClass?: string;
}

@Injectable({ providedIn: 'root' })
export class AppDialogService {
  private readonly dialogs = inject(DialogService);

  open<TData, TResult>(
    component: Type<unknown>,
    data: TData,
    options: AppDialogOptions = {},
  ): Promise<TResult | undefined> {
    const dismissible = options.dismissible ?? true;
    const config: DynamicDialogConfig<TData> = {
      data,
      header: options.header,
      width: options.width ?? 'min(640px, 94vw)',
      modal: true,
      closable: dismissible,
      closeOnEscape: dismissible,
      dismissableMask: dismissible,
      focusOnShow: options.focusOnShow ?? true,
      focusTrap: true,
      styleClass: options.styleClass,
      contentStyle: options.maxWidth ? { maxWidth: options.maxWidth } : undefined,
    };

    const ref = this.dialogs.open(component, config) as DynamicDialogRef<TResult> | null;
    if (!ref) {
      return Promise.resolve(undefined);
    }
    return firstValueFrom(
      merge(ref.onClose, ref.onDestroy.pipe(map(() => undefined as TResult | undefined))).pipe(
        take(1),
      ),
    );
  }
}
