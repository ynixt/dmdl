import { CanDeactivateFn } from '@angular/router';
import { SettingsPage } from './settings.page';

export const settingsLeaveGuard: CanDeactivateFn<SettingsPage> = (component) =>
  component.canLeave();
