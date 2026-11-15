import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import en from '../../../../public/i18n/en-US.json';
import pt from '../../../../public/i18n/pt-BR.json';
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
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService(),
        {
          provide: AuthService,
          useValue: { isAuthenticated: authenticated, currentOnboardingCompleted: completed },
        },
      ],
    });
  });

  it.each([false, null])('shows logs during onboarding while completion is %s', (state) => {
    completed.set(state);
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('nav a')).toHaveLength(1);
    expect(fixture.nativeElement.querySelector('nav a').getAttribute('href')).toBe('/logs');
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
      '/logs',
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

describe('Navbar repository link and version', () => {
  const SHA = '2f5cac4e0d1b2a3c4d5e6f708192a3b4c5d6e7f8';
  const authenticated = signal(true);
  let http: HttpTestingController;

  beforeEach(() => {
    authenticated.set(true);
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTranslateService(),
        {
          provide: AuthService,
          useValue: { isAuthenticated: authenticated, currentOnboardingCompleted: signal(true) },
        },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  function render(): ComponentFixture<NavbarComponent> {
    const fixture = TestBed.createComponent(NavbarComponent);
    fixture.detectChanges();
    return fixture;
  }

  function query(fixture: ComponentFixture<NavbarComponent>, id: string): HTMLElement | null {
    return fixture.nativeElement.querySelector(`[data-testid="${id}"]`);
  }

  function respond(
    fixture: ComponentFixture<NavbarComponent>,
    body: object | 404,
  ): HTMLElement | null {
    const request = http.expectOne('/version.json');
    if (body === 404) request.flush('', { status: 404, statusText: 'Not Found' });
    else request.flush(body);
    fixture.detectChanges();
    return query(fixture, 'app-version');
  }

  it.each([
    ['en-US', en],
    ['pt-BR', pt],
  ] as const)('uses Downloads in the %s navigation', (language, translations) => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation(language, translations);
    translate.use(language);
    const fixture = render();
    respond(fixture, { version: null, commit: null });
    expect(fixture.nativeElement.querySelector('a[href="/downloads"]').textContent.trim()).toBe(
      'Downloads',
    );
  });

  it('shows only the repository link to visitors and does not request the version', () => {
    authenticated.set(false);
    const fixture = render();
    const github = query(fixture, 'github-link') as HTMLAnchorElement;

    expect(github.getAttribute('href')).toBe('https://github.com/ynixt/dmdl');
    expect(github.target).toBe('_blank');
    expect(github.rel).toBe('noopener noreferrer');
    expect(github.getAttribute('aria-label')).toBe('KZ.GITHUB_REPOSITORY');
    expect(github.parentElement?.previousElementSibling?.textContent).toBe(
      'Debrid Media Downloader',
    );
    expect(github.querySelector('i')?.classList.contains('text-xs')).toBe(true);
    expect(query(fixture, 'app-version')).toBeNull();
    http.expectNone('/version.json');
  });

  it('places the compact repository icon immediately before the version under the title', () => {
    const fixture = render();
    const version = respond(fixture, { version: 'v0.0.6', commit: SHA }) as HTMLElement;
    const title = fixture.nativeElement.querySelector('a[href="/discovery"]') as HTMLElement;
    expect(title.nextElementSibling).toBe(version.parentElement);
    const github = query(fixture, 'github-link')!;
    expect(github.nextElementSibling).toBe(version);
    expect(github.querySelector('i')?.classList.contains('text-xs')).toBe(true);
    expect(github.parentElement?.classList.contains('items-center')).toBe(true);
  });

  it('links a release tag to its release', () => {
    const version = respond(render(), { version: 'v0.0.6', commit: SHA }) as HTMLAnchorElement;
    expect(version.tagName).toBe('A');
    expect(version.textContent?.trim()).toBe('v0.0.6');
    expect(version.getAttribute('href')).toBe('https://github.com/ynixt/dmdl/releases/tag/v0.0.6');
    expect(version.target).toBe('_blank');
  });

  it('links an untagged build to its full commit', () => {
    const version = respond(render(), { version: null, commit: SHA }) as HTMLAnchorElement;
    expect(version.textContent?.trim()).toBe('2f5cac4');
    expect(version.getAttribute('href')).toBe(`https://github.com/ynixt/dmdl/commit/${SHA}`);
  });

  it.each<[string, object | 404]>([
    ['local', { version: null, commit: null }],
    ['dev', 404],
  ])('shows %s as plain text', (label, body) => {
    const version = respond(render(), body);
    expect(version?.tagName).toBe('SPAN');
    expect(version?.textContent?.trim()).toBe(label);
  });

  it('requests the version after signing in', () => {
    authenticated.set(false);
    const fixture = render();
    http.expectNone('/version.json');
    authenticated.set(true);
    fixture.detectChanges();
    expect(respond(fixture, { version: 'v0.0.6', commit: SHA })?.textContent?.trim()).toBe(
      'v0.0.6',
    );
  });
});
