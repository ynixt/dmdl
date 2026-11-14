import { Component, input } from '@angular/core';
import { TranslatePipe } from '@ngx-translate/core';

@Component({
  selector: 'app-settings-feedback',
  imports: [TranslatePipe],
  styleUrl: './debrid.scss',
  template: `
    @if (error()) {
      <p role="alert" class="error">{{ error() | translate }}</p>
    }
    @if (message()) {
      <p role="status">{{ message() | translate }}</p>
    }
  `,
})
export class SettingsFeedbackComponent {
  readonly error = input('');
  readonly message = input('');
}
