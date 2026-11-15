import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  ElementRef,
  forwardRef,
  inject,
  Injector,
  input,
  OnInit,
  signal,
  ViewChild,
} from '@angular/core';
import { ControlValueAccessor, FormsModule, NG_VALUE_ACCESSOR } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { Password, PasswordModule } from 'primeng/password';

/**
 * - `current-password`: the user's own password when signing in.
 * - `new-password`: the user's own password when creating or changing it.
 * - `secret`: any other secret (API tokens, addon manifest URLs) that password managers must
 *   neither autofill nor offer to save.
 */
export type SecretInputKind = 'current-password' | 'new-password' | 'secret';

/**
 * Masked input with a keyboard-accessible show/hide control, usable with both
 * `formControlName` and `ngModel`. The inner `<input>` receives `inputId`, so `<label for>`
 * keeps pointing at the real field. It starts masked unless `revealed` is set when it is created,
 * for example for a value the user is typing for the first time.
 */
@Component({
  selector: 'app-secret-input',
  imports: [FormsModule, PasswordModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  providers: [
    {
      provide: NG_VALUE_ACCESSOR,
      useExisting: forwardRef(() => SecretInputComponent),
      multi: true,
    },
  ],
  templateUrl: './secret-input.component.html',
  styleUrl: './secret-input.component.scss',
})
export class SecretInputComponent implements ControlValueAccessor, OnInit {
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  readonly inputId = input.required<string>();
  readonly kind = input<SecretInputKind>('secret');
  readonly placeholder = input<string | undefined>(undefined);
  readonly describedBy = input<string | null>(null);
  readonly invalid = input(false);
  readonly disabled = input(false);
  /** Initial visibility only; later changes are left to the show/hide control. */
  readonly revealed = input(false);

  @ViewChild(Password, { static: true }) private readonly password!: Password;

  protected readonly value = signal<string | null>(null);
  private readonly formDisabled = signal(false);
  protected readonly isDisabled = computed(() => this.disabled() || this.formDisabled());

  protected readonly autocomplete = computed(() =>
    this.kind() === 'current-password' ? 'current-password' : 'new-password',
  );

  /** Passthrough attributes for the inner `<input>`; `null` removes an attribute. */
  protected readonly passthrough = computed(() => {
    const secret = this.kind() === 'secret';
    return {
      pcInputText: {
        root: {
          'aria-describedby': this.describedBy() || null,
          'aria-invalid': this.invalid() ? 'true' : null,
          // Ask 1Password, LastPass and Bitwarden to leave non-password secrets alone.
          'data-1p-ignore': secret ? 'true' : null,
          'data-lpignore': secret ? 'true' : null,
          'data-bwignore': secret ? 'true' : null,
        },
      },
    };
  });

  ngOnInit(): void {
    this.password.unmasked = this.revealed();
  }

  private onChange: (value: string | null) => void = () => undefined;
  private onTouched: () => void = () => undefined;

  writeValue(value: string | null | undefined): void {
    this.value.set(value ?? null);
  }

  registerOnChange(fn: (value: string | null) => void): void {
    this.onChange = fn;
  }

  registerOnTouched(fn: () => void): void {
    this.onTouched = fn;
  }

  setDisabledState(isDisabled: boolean): void {
    this.formDisabled.set(isDisabled);
  }

  protected updateValue(value: string | null): void {
    this.value.set(value);
    this.onChange(value);
  }

  protected markTouched(): void {
    this.onTouched();
  }

  /**
   * PrimeNG swaps the show and hide templates when the mask toggles, so the button that was just
   * activated is destroyed. Move focus to its replacement so keyboard users keep their place.
   */
  protected keepToggleFocus(event: Event): void {
    if (event.currentTarget !== document.activeElement) {
      return;
    }
    afterNextRender(
      () =>
        this.host.nativeElement.querySelector<HTMLButtonElement>('.secret-input-toggle')?.focus(),
      { injector: this.injector },
    );
  }
}
