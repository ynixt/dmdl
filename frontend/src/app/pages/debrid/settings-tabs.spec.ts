import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { provideTranslateService, TranslateService } from '@ngx-translate/core';
import { firstValueFrom, of } from 'rxjs';
import en from '../../../../public/i18n/en-US.json';
import { ConfirmationService } from 'primeng/api';
import { ConfirmDialog } from 'primeng/confirmdialog';
import { I18nService } from '../../core/i18n/i18n.service';
import { AppConfirmationService } from '../../core/ui/confirmation.service';
import { Addon, Addons, DebridApi, Settings, Webhook } from '../../services/debrid-api.service';
import { SettingsPage } from './settings.page';
import { settingsLeaveGuard } from './settings-leave.guard';
import { SettingsSearchTabComponent } from './settings-search-tab.component';
import { SettingsWebhooksTabComponent } from './settings-webhooks-tab.component';

@Component({ template: 'Downloads' })
class DestinationPage {}

describe('Settings tabs and independent drafts', () => {
  const addon = (id: string): Addon => ({
    id,
    name: id,
    manifestUrl: `https://${id}/manifest.json`,
    revision: 3,
    enabled: true,
    position: 0,
    manifest: { name: id, catalogs: [] },
  });
  let settings: Settings;
  let addons: Addons;
  let webhooks: Webhook[];
  const api = { get: vi.fn(), put: vi.fn(), post: vi.fn() };
  const confirmations = { confirm: vi.fn(async () => false) };
  const i18n = { currentLanguage: vi.fn(() => 'en-US'), setLanguage: vi.fn(() => of(void 0)) };
  let harness: RouterTestingHarness;

  beforeEach(() => {
    vi.resetAllMocks();
    confirmations.confirm.mockResolvedValue(false);
    i18n.currentLanguage.mockReturnValue('en-US');
    i18n.setLanguage.mockReturnValue(of(void 0));
    const category = () => ({
      folderTemplate: '{title}',
      destinations: [{ id: crypto.randomUUID(), name: 'HDD', path: '/media' }],
      defaultDestinationId: null,
    });
    settings = {
      username: 'fixture',
      locale: 'en-US',
      storageRoot: '/media',
      onboardingCompleted: true,
      configurationRevision: 1,
      organization: {
        revision: 1,
        categories: { MOVIE: category(), SERIES: category(), ANIME: category() },
      },
      transfer: {
        split: 2,
        maxConnectionPerServer: 2,
        minSplitSizeMib: 16,
        maxTries: 5,
        retryWaitSeconds: 3,
        fileAllocation: 'PREALLOC',
        ipv6Enabled: false,
      },
      maxConcurrentDownloads: 2,
      streamBadgeJson: '{"imports":[]}',
    };
    addons = { metadata: addon('metadata'), search: [addon('first'), addon('second')] };
    webhooks = [
      {
        id: 'hook-1',
        name: 'Home Assistant',
        method: 'POST',
        url: 'https://ha.local/api/webhook/secret-id',
        headers: [{ name: 'Authorization', value: 'Bearer stored-token' }],
        bodyMode: 'NONE',
        body: null,
        events: ['download.completed'],
        manual: true,
        enabled: true,
      },
    ];
    api.get.mockImplementation(async (path: string) =>
      structuredClone(
        path === '/settings'
          ? settings
          : path === '/addons'
            ? addons
            : path === '/webhooks'
              ? webhooks
              : { streamBadgeJson: '{"imports":[]}' },
      ),
    );
    api.put.mockImplementation(async (path: string, body: unknown) => {
      if (path === '/webhooks') {
        webhooks = structuredClone(body as Webhook[]);
        return structuredClone(webhooks);
      }
      if (path === '/addons/search') {
        addons.search = (body as Addon[]).map((row, position) => ({
          ...addon(row.id ?? 'new'),
          ...row,
          position,
        }));
        return structuredClone(addons);
      }
      if (path === '/addons/metadata') {
        addons.metadata = { ...addons.metadata!, ...(body as Partial<Addon>) };
        return structuredClone({ metadata: addons.metadata });
      }
      settings = { ...settings, ...(body as Partial<Settings>) };
      return structuredClone(settings);
    });
    TestBed.configureTestingModule({
      providers: [
        provideRouter([
          { path: 'settings', component: SettingsPage, canDeactivate: [settingsLeaveGuard] },
          { path: 'downloads', component: DestinationPage },
        ]),
        provideTranslateService(),
        ConfirmationService,
        { provide: DebridApi, useValue: api },
        { provide: AppConfirmationService, useValue: confirmations },
        { provide: I18nService, useValue: i18n },
      ],
    });
  });

  async function settle(fixture: ComponentFixture<unknown>): Promise<void> {
    for (let pass = 0; pass < 3; pass++) {
      fixture.detectChanges();
      await fixture.whenStable();
    }
    fixture.detectChanges();
  }
  async function open(url = '/settings'): Promise<SettingsPage> {
    harness = await RouterTestingHarness.create();
    const page = await harness.navigateByUrl(url, SettingsPage);
    await vi.waitFor(() => expect(page.store.loaded()).toBe(true));
    await settle(harness.fixture);
    return page;
  }
  async function tab(
    page: SettingsPage,
    name: 'metadata' | 'search' | 'storage' | 'webhooks',
  ): Promise<void> {
    page.selectTab(name);
    await settle(harness.fixture);
  }
  function type(selector: string, value: string): void {
    const field = harness.routeNativeElement!.querySelector(selector) as HTMLInputElement;
    field.value = value;
    field.dispatchEvent(new Event('input', { bubbles: true }));
  }

  it('defaults to Metadata, reflects tabs in the URL and falls back for unknown values', async () => {
    const page = await open();
    expect(page.activeTab()).toBe('metadata');
    expect(harness.routeNativeElement!.querySelector('#metadata-url')).not.toBeNull();
    expect(harness.routeNativeElement!.querySelector('#webhook-0-url')).toBeNull();
    expect(
      harness.routeNativeElement!.querySelector('.wizard-footer button')?.hasAttribute('disabled'),
    ).toBe(true);
    await tab(page, 'storage');
    expect(TestBed.inject(Router).url).toBe('/settings?tab=storage');
    expect(harness.routeNativeElement!.querySelector('#metadata-url')).toBeNull();
    await harness.navigateByUrl('/settings?tab=unknown');
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('metadata');
    expect(TestBed.inject(Router).url).toBe('/settings?tab=metadata');
    await harness.navigateByUrl('/settings?tab=jellyfin');
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('metadata');
  });

  it('opens the requested tab and supports arrow, Home and End keyboard navigation', async () => {
    const page = await open('/settings?tab=storage');
    expect(page.activeTab()).toBe('storage');
    const buttons = harness.routeNativeElement!.querySelectorAll<HTMLButtonElement>('[role=tab]');
    buttons[2].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }));
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('webhooks');
    expect(document.activeElement).toBe(buttons[3]);
    buttons[3].dispatchEvent(new KeyboardEvent('keydown', { key: 'Home', bubbles: true }));
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('metadata');
    buttons[0].dispatchEvent(new KeyboardEvent('keydown', { key: 'End', bubbles: true }));
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('webhooks');
    buttons[3].dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', bubbles: true }));
    await settle(harness.fixture);
    expect(page.activeTab()).toBe('storage');
  });

  it('keeps a Storage draft and dirty indicator across a Search save and component recreation', async () => {
    const page = await open('/settings?tab=storage');
    type('[id^="storage-folder-"]', 'draft/{title}');
    await settle(harness.fixture);
    expect(page.store.storage.dirty()).toBe(true);
    await tab(page, 'search');
    const search = harness.routeDebugElement!.query(By.directive(SettingsSearchTabComponent))
      .componentInstance as SettingsSearchTabComponent;
    search.edit(0, { enabled: false });
    search.move(0, 1);
    expect(api.put).not.toHaveBeenCalled();
    await page.store.saveSearch();
    expect(api.put).toHaveBeenCalledTimes(1);
    expect(page.store.search.dirty()).toBe(false);
    expect(page.store.storage.dirty()).toBe(true);
    await tab(page, 'storage');
    expect(
      (harness.routeNativeElement!.querySelector('[id^="storage-folder-"]') as HTMLInputElement)
        .value,
    ).toBe('draft/{title}');
    expect(
      harness.routeNativeElement!.querySelector('#settings-tab-storage .unsaved-dot'),
    ).not.toBeNull();
  });

  it('saves a metadata rename with only the metadata endpoint', async () => {
    const page = await open();
    type('#metadata-name', 'Renamed');
    await settle(harness.fixture);
    await page.store.saveMetadata();
    expect(api.put).toHaveBeenCalledExactlyOnceWith('/addons/metadata', {
      name: 'Renamed',
      manifestUrl: 'https://metadata/manifest.json',
    });
    expect(page.store.metadata.dirty()).toBe(false);
  });

  it('updates successful parts after partial save failure and retries only the failed part', async () => {
    const page = await open();
    page.store.metadata.patch({
      addon: { ...page.store.metadata.draft().addon, name: 'Saved addon' },
      locale: 'pt-BR',
    });
    api.put.mockImplementationOnce(async () => ({
      metadata: { ...addons.metadata!, name: 'Saved addon' },
    }));
    api.put.mockRejectedValueOnce({ error: { error: 'KZ.REQUEST_FAILED' } });
    await page.store.saveMetadata();
    expect(page.store.metadata.baseline().addon.name).toBe('Saved addon');
    expect(page.store.metadata.dirty()).toBe(true);
    await page.store.saveMetadata();
    expect(api.put.mock.calls.map(([path]) => path)).toEqual([
      '/addons/metadata',
      '/settings',
      '/settings',
    ]);
    expect(i18n.setLanguage).toHaveBeenCalledWith('pt-BR');
    expect(page.store.metadata.dirty()).toBe(false);
  });

  it('places indexed URL errors by the failed search row and shows all-disabled errors', async () => {
    const page = await open('/settings?tab=search');
    page.store.search.patch({
      addons: page.store.search.draft().addons.map((row) => ({ ...row, enabled: false })),
    });
    api.put.mockRejectedValueOnce({
      error: {
        error: 'KZ.ADDONS_INVALID',
        errors: {
          'search.1.manifestUrl': 'KZ.ADDON_INVALID_URL',
          search: 'KZ.ADDON_SEARCH_REQUIRED',
        },
      },
    });
    await page.store.saveSearch();
    await settle(harness.fixture);
    expect(harness.routeNativeElement!.querySelector('#search-url-error-1')?.textContent).toContain(
      'KZ.ADDON_INVALID_URL',
    );
    expect(harness.routeNativeElement!.querySelector('#search-url-error-0')).toBeNull();
    expect(harness.routeNativeElement!.textContent).toContain('KZ.ADDON_SEARCH_REQUIRED');
    expect(page.store.search.dirty()).toBe(true);
  });

  it('shows translated missing-resource causes beside metadata and search URLs and keeps drafts', async () => {
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en-US', en);
    await firstValueFrom(translate.use('en-US'));
    const page = await open();
    page.store.metadata.patch({
      addon: {
        ...page.store.metadata.draft().addon,
        manifestUrl: 'https://no-catalog/manifest.json',
      },
    });
    api.put.mockRejectedValueOnce({
      error: {
        error: 'KZ.INVALID_CONFIGURATION',
        errors: { 'metadata.manifestUrl': 'KZ.ADDON_MISSING_CATALOG' },
      },
    });
    await page.store.saveMetadata();
    await settle(harness.fixture);
    expect(harness.routeNativeElement!.querySelector('#metadata-url-error')?.textContent).toContain(
      en.KZ.ADDON_MISSING_CATALOG,
    );
    expect(page.store.metadata.draft().addon.manifestUrl).toBe('https://no-catalog/manifest.json');
    expect(page.store.metadata.dirty()).toBe(true);

    await tab(page, 'search');
    page.store.search.patch({
      addons: page.store.search
        .draft()
        .addons.map((row, index) =>
          index === 1 ? { ...row, manifestUrl: 'https://no-stream/manifest.json' } : row,
        ),
    });
    api.put.mockRejectedValueOnce({
      error: {
        error: 'KZ.ADDONS_INVALID',
        errors: { 'search.1.manifestUrl': 'KZ.ADDON_MISSING_STREAM' },
      },
    });
    await page.store.saveSearch();
    await settle(harness.fixture);
    expect(harness.routeNativeElement!.querySelector('#search-url-error-1')?.textContent).toContain(
      en.KZ.ADDON_MISSING_STREAM,
    );
    expect(harness.routeNativeElement!.querySelector('#search-url-error-0')).toBeNull();
    expect(page.store.search.draft().addons[1].manifestUrl).toBe('https://no-stream/manifest.json');
    expect(page.store.search.dirty()).toBe(true);
  });

  it('saves badges after the list and leaves only badges dirty when that request fails', async () => {
    const page = await open('/settings?tab=search');
    page.store.search.patch({
      addons: page.store.search.draft().addons.slice(1),
      streamBadgeJson: '{"filters":[]}',
    });
    api.put.mockImplementationOnce(async () => ({ ...addons, search: [addons.search[1]] }));
    api.put.mockRejectedValueOnce({ error: { error: 'KZ.BADGE_JSON_INVALID' } });
    await page.store.saveSearch();
    expect(page.store.search.baseline().addons).toEqual(page.store.search.draft().addons);
    expect(page.store.search.dirty()).toBe(true);
    await page.store.saveSearch();
    expect(api.put.mock.calls.map(([path]) => path)).toEqual([
      '/addons/search',
      '/settings',
      '/settings',
    ]);
    expect(page.store.search.dirty()).toBe(false);
  });

  it('omits organization when only transfer fields change', async () => {
    const page = await open('/settings?tab=storage');
    type('#transfer-tries', '8');
    await settle(harness.fixture);
    expect(page.store.storage.dirty()).toBe(true);
    await page.store.saveStorage();
    expect(api.put).toHaveBeenCalledExactlyOnceWith('/settings', {
      transfer: expect.objectContaining({ maxTries: 8 }),
      finalization: { hashDownloadedFile: true, verifyCopy: false, verifyPlacedFile: false },
      maxConcurrentDownloads: 2,
    });
    expect(page.store.storage.dirty()).toBe(false);
  });

  it('masks webhook secrets, saves the whole list and tests the values being edited', async () => {
    const page = await open('/settings?tab=webhooks');
    const url = harness.routeNativeElement!.querySelector<HTMLInputElement>('#webhook-0-url')!;
    const header = harness.routeNativeElement!.querySelector<HTMLInputElement>(
      '#webhook-0-headers-0-value',
    )!;
    expect(url.type).toBe('password');
    expect(url.value).toBe('https://ha.local/api/webhook/secret-id');
    expect(header.type).toBe('password');
    expect(header.value).toBe('Bearer stored-token');
    type('#webhook-0-url', 'https://draft.local/hook');
    await settle(harness.fixture);
    expect(page.store.webhooks.dirty()).toBe(true);
    const component = harness.routeDebugElement!.query(By.directive(SettingsWebhooksTabComponent))
      .componentInstance as SettingsWebhooksTabComponent;
    api.post.mockResolvedValueOnce({ ok: true, status: 204 });
    await component.test(page.store.webhooks.draft().items[0]);
    expect(api.post).toHaveBeenCalledExactlyOnceWith('/webhooks/test', {
      webhook: expect.objectContaining({ id: 'hook-1', url: 'https://draft.local/hook' }),
      event: 'download.completed',
    });
    expect(webhooks[0].url).toBe('https://ha.local/api/webhook/secret-id');
    await page.store.saveWebhooks();
    expect(api.put).toHaveBeenCalledExactlyOnceWith('/webhooks', [
      expect.objectContaining({ id: 'hook-1', url: 'https://draft.local/hook' }),
    ]);
    expect(page.store.webhooks.dirty()).toBe(false);
  });

  it('places webhook field errors beside their fields by index', async () => {
    const page = await open('/settings?tab=webhooks');
    page.store.webhooks.patch({
      items: [{ ...page.store.webhooks.draft().items[0], url: '', events: [], manual: false }],
    });
    api.put.mockRejectedValueOnce({
      error: {
        error: 'KZ.WEBHOOKS_INVALID',
        errors: {
          'webhooks.0.url': 'KZ.WEBHOOK_URL_REQUIRED',
          'webhooks.0.events': 'KZ.WEBHOOK_TRIGGER_REQUIRED',
        },
      },
    });
    await page.store.saveWebhooks();
    await settle(harness.fixture);
    expect(
      harness.routeNativeElement!.querySelector('#webhook-0-url-error')?.textContent,
    ).toContain('KZ.WEBHOOK_URL_REQUIRED');
    expect(
      harness.routeNativeElement!.querySelector('#webhook-0-events-error')?.textContent,
    ).toContain('KZ.WEBHOOK_TRIGGER_REQUIRED');
    expect(page.store.webhooks.dirty()).toBe(true);
  });

  it('fills a new webhook from a preset and removing one is only a draft', async () => {
    const page = await open('/settings?tab=webhooks');
    const component = harness.routeDebugElement!.query(By.directive(SettingsWebhooksTabComponent))
      .componentInstance as SettingsWebhooksTabComponent;
    component.preset.set('jellyfin');
    component.add();
    await settle(harness.fixture);
    const added = page.store.webhooks.draft().items[1];
    expect(added).toEqual(
      expect.objectContaining({
        name: 'Jellyfin',
        method: 'POST',
        bodyMode: 'NONE',
        events: ['download.completed'],
      }),
    );
    expect(added.url).toMatch(/\/Library\/Refresh$/);
    expect(added.headers[0].value).toContain('MediaBrowser Token=');
    expect(component.bodyLanguage(added)).toBe('text');
    const header = (value: string) => ({ ...added, headers: [{ name: ' content-type ', value }] });
    expect(component.bodyLanguage(header('application/json; charset=utf-8'))).toBe('json');
    expect(component.bodyLanguage(header('application/ld+json'))).toBe('json');
    expect(component.bodyLanguage(header('text/plain'))).toBe('text');
    const body = (value: string) => ({ ...added, body: value });
    expect(component.bodyLanguage(body('  {"state": "{{download.state}}"}'))).toBe('json');
    expect(component.jsonWithoutContentType(body('[1]'))).toBe(true);
    expect(component.bodyLanguage(body('{{title}} finished'))).toBe('text');
    expect(component.jsonWithoutContentType(body('{{title}} finished'))).toBe(false);
    expect(
      component.jsonWithoutContentType({
        ...header('application/json'),
        body: '{"a": 1}',
      }),
    ).toBe(false);
    const input = (id: string) =>
      harness.routeNativeElement!.querySelector<HTMLInputElement>(`#${id}`)!;
    expect(input('webhook-1-url').type).toBe('text');
    expect(input('webhook-1-headers-0-value').type).toBe('text');
    expect(input('webhook-0-url').type).toBe('password');
    component.addHeader(0);
    await settle(harness.fixture);
    expect(input('webhook-0-headers-0-value').type).toBe('password');
    expect(input('webhook-0-headers-1-value').type).toBe('text');
    component.remove(0);
    await settle(harness.fixture);
    expect(page.store.webhooks.draft().items.map((item) => item.name)).toEqual(['Jellyfin']);
    expect(webhooks.map((item) => item.name)).toEqual(['Home Assistant']);
    expect(api.put).not.toHaveBeenCalled();
  });

  it('disables only the saving tab and keeps success and errors scoped when switching', async () => {
    const page = await open('/settings?tab=search');
    page.store.search.patch({ addons: page.store.search.draft().addons.slice(1) });
    let finish!: (value: Addons) => void;
    api.put.mockImplementationOnce(
      () =>
        new Promise<Addons>((resolve) => {
          finish = resolve;
        }),
    );
    const saving = page.store.saveSearch();
    await settle(harness.fixture);
    expect(harness.routeNativeElement!.querySelector('fieldset')?.disabled).toBe(true);
    await tab(page, 'webhooks');
    expect(harness.routeNativeElement!.querySelector('fieldset')?.disabled).toBe(false);
    type('#webhook-0-name', 'Draft name');
    await settle(harness.fixture);
    finish({ ...addons, search: [addons.search[1]] });
    await saving;
    await settle(harness.fixture);
    expect(page.store.webhooks.dirty()).toBe(true);
    expect(harness.routeNativeElement!.textContent).not.toContain('KZ.SAVED');
    await tab(page, 'search');
    expect(harness.routeNativeElement!.textContent).toContain('KZ.SAVED');
  });

  it('asks on navigation away, preserves drafts on cancel and warns before unload', async () => {
    const page = await open();
    type('#metadata-name', 'Unsaved');
    await settle(harness.fixture);
    const event = new Event('beforeunload', { cancelable: true });
    window.dispatchEvent(event);
    expect(event.defaultPrevented).toBe(true);
    await tab(page, 'search');
    expect(confirmations.confirm).not.toHaveBeenCalled();
    expect(await TestBed.inject(Router).navigateByUrl('/downloads')).toBe(false);
    expect(confirmations.confirm).toHaveBeenCalledTimes(1);
    expect(page.store.metadata.draft().addon.name).toBe('Unsaved');
    confirmations.confirm.mockResolvedValue(true);
    expect(await TestBed.inject(Router).navigateByUrl('/downloads')).toBe(true);
  });

  it('renders the real confirmation dialog and cancelling keeps the draft', async () => {
    TestBed.overrideProvider(AppConfirmationService, {
      useFactory: () => new AppConfirmationService(),
    });
    const page = await open();
    type('#metadata-name', 'Keep this draft');
    await settle(harness.fixture);
    const navigation = TestBed.inject(Router).navigateByUrl('/downloads');
    await vi.waitFor(() => {
      harness.fixture.detectChanges();
      expect(document.querySelector('[role="alertdialog"]')).not.toBeNull();
    });
    const reject = await vi.waitFor(() => {
      harness.fixture.detectChanges();
      const button = Array.from(
        document.querySelectorAll<HTMLButtonElement>('[role="alertdialog"] button'),
      ).find((entry) => entry.textContent?.includes('KZ.KEEP_EDITING'));
      expect(button).toBeDefined();
      return button!;
    });
    reject.click();
    expect(await navigation).toBe(false);
    const dialog = harness.routeDebugElement!.query(By.directive(ConfirmDialog))
      .componentInstance as ConfirmDialog;
    expect(() => dialog.onVisibleChange(false)).not.toThrow();
    expect(page.store.metadata.draft().addon.name).toBe('Keep this draft');
    expect(page.store.metadata.dirty()).toBe(true);
    await tab(page, 'storage');
    expect(harness.routeNativeElement!.querySelector('#storage-folder-MOVIE')).not.toBeNull();
  });
});
