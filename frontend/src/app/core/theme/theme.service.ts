import { Injectable, signal } from '@angular/core';

const THEME_STORAGE_KEY = 'theme';
type Theme = 'dark' | 'light';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly darkTheme = signal(true);

  readonly isDarkTheme = this.darkTheme.asReadonly();

  initialize(): void {
    const savedTheme = (localStorage.getItem(THEME_STORAGE_KEY) as Theme | null) ?? 'dark';
    this.applyTheme(savedTheme);
  }

  setDarkTheme(isDark: boolean): void {
    this.applyTheme(isDark ? 'dark' : 'light');
  }

  private applyTheme(theme: Theme): void {
    const isDark = theme === 'dark';
    document.documentElement.classList.toggle('dark-theme', isDark);
    document.documentElement.classList.toggle('light-theme', !isDark);
    localStorage.setItem(THEME_STORAGE_KEY, theme);
    this.darkTheme.set(isDark);
  }
}
