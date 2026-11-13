import { TestBed } from '@angular/core/testing';
import { ThemeService } from './theme.service';

describe('ThemeService', () => {
  let service: ThemeService;

  beforeEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark-theme', 'light-theme');
    TestBed.configureTestingModule({});
    service = TestBed.inject(ThemeService);
  });

  afterEach(() => {
    localStorage.clear();
    document.documentElement.classList.remove('dark-theme', 'light-theme');
  });

  it('defaults to the dark theme and applies the root class', () => {
    service.initialize();

    expect(service.isDarkTheme()).toBe(true);
    expect(document.documentElement.classList.contains('dark-theme')).toBe(true);
    expect(document.documentElement.classList.contains('light-theme')).toBe(false);
    expect(localStorage.getItem('theme')).toBe('dark');
  });

  it('restores a stored light theme before rendering', () => {
    localStorage.setItem('theme', 'light');

    service.initialize();

    expect(service.isDarkTheme()).toBe(false);
    expect(document.documentElement.classList.contains('light-theme')).toBe(true);
    expect(document.documentElement.classList.contains('dark-theme')).toBe(false);
  });

  it('persists theme toggles and keeps root classes mutually exclusive', () => {
    service.setDarkTheme(false);
    expect(localStorage.getItem('theme')).toBe('light');
    expect(document.documentElement.className).toContain('light-theme');

    service.setDarkTheme(true);
    expect(localStorage.getItem('theme')).toBe('dark');
    expect(document.documentElement.classList.contains('dark-theme')).toBe(true);
    expect(document.documentElement.classList.contains('light-theme')).toBe(false);
  });
});
