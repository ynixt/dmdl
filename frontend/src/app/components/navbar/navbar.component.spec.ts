import { signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';
import { AuthService } from '../../core/auth/auth.service';
import { NavbarComponent } from './navbar.component';

describe('Onboarding navigation', () => {
  const authenticated = signal(true);
  const completed = signal<boolean | null>(false);

  beforeEach(() => {
    authenticated.set(true);
    completed.set(false);
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideTranslateService(),
        {
          provide: AuthService,
          useValue: { isAuthenticated: authenticated, currentOnboardingCompleted: completed },
        },
      ],
    });
  });

  it.each([false, null])('hides application links while completion is %s', (state) => {
    completed.set(state);
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('nav a')).toHaveLength(0);
    expect(fixture.nativeElement.querySelector('nav button')).not.toBeNull();
  });

  it('shows all application links as soon as setup completes', () => {
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    completed.set(true);
    fixture.detectChanges();
    const links = Array.from(
      fixture.nativeElement.querySelectorAll('nav a'),
    ) as HTMLAnchorElement[];
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/discovery',
      '/downloads',
      '/settings',
    ]);
  });

  it('hides the authenticated navigation after logout', () => {
    completed.set(true);
    authenticated.set(false);
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('nav')).toBeNull();
  });

  it('hides logout on the offline page while keeping guarded navigation links', () => {
    completed.set(true);
    vi.spyOn(TestBed.inject(Router), 'url', 'get').mockReturnValue(
      '/offline?returnUrl=%2Fdownloads',
    );
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('nav a')).toHaveLength(3);
    expect(fixture.nativeElement.querySelector('nav button')).toBeNull();
  });
});
