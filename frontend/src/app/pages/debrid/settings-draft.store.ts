import { computed, inject, Injectable, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { I18nService, toSupportedLanguage } from '../../core/i18n/i18n.service';
import {
  Addon,
  Addons,
  DebridApi,
  Organization,
  SearchAddonInput,
  Settings,
  Transfer,
} from '../../services/debrid-api.service';

export type SettingsTab = 'metadata' | 'search' | 'storage' | 'jellyfin';
export interface MetadataDraft {
  locale: string;
  addon: { name: string; manifestUrl: string };
}
export interface SearchDraft {
  addons: SearchAddonInput[];
  streamBadgeJson: string;
}
export interface StorageDraft {
  organization: Organization;
  transfer: Transfer;
  maxConcurrentDownloads: number;
}
export type JellyfinDraft = Settings['jellyfin'];
export const equal = (left: unknown, right: unknown): boolean =>
  JSON.stringify(left) === JSON.stringify(right);
const clone = <T>(value: T): T => structuredClone(value);

export class SettingsSlice<T extends object> {
  readonly baseline;
  readonly draft;
  readonly dirty = computed(() => !equal(this.baseline(), this.draft()));
  readonly saving = signal(false);
  readonly errors = signal<Record<string, string>>({});
  readonly error = signal('');
  readonly message = signal('');

  constructor(initial: T) {
    this.baseline = signal(clone(initial));
    this.draft = signal(clone(initial));
  }

  initialize(value: T): void {
    this.baseline.set(clone(value));
    this.draft.set(clone(value));
  }

  patch(value: Partial<T>): void {
    this.draft.update((current) => ({ ...current, ...value }));
    this.message.set('');
  }

  touch(): void {
    this.patch({});
  }

  // Commit only the successful part of a multi-request save, leaving other drafts intact.
  commit(value: Partial<T>): void {
    this.baseline.update((current) => ({ ...current, ...clone(value) }));
    this.draft.update((current) => ({ ...current, ...clone(value) }));
  }
}

@Injectable()
export class SettingsDraftStore {
  private readonly api = inject(DebridApi);
  private readonly i18n = inject(I18nService);
  readonly loaded = signal(false);
  readonly loading = signal(false);
  readonly loadError = signal('');
  readonly defaultBadges = signal('');
  readonly metadata = new SettingsSlice<MetadataDraft>({
    locale: 'pt-BR',
    addon: { name: '', manifestUrl: '' },
  });
  readonly search = new SettingsSlice<SearchDraft>({ addons: [], streamBadgeJson: '' });
  readonly storage = new SettingsSlice<StorageDraft>({
    organization: {
      revision: 1,
      categories: {
        MOVIE: { folderTemplate: '', destinations: [], defaultDestinationId: null },
        SERIES: { folderTemplate: '', destinations: [], defaultDestinationId: null },
        ANIME: { folderTemplate: '', destinations: [], defaultDestinationId: null },
      },
    },
    maxConcurrentDownloads: 1,
    transfer: {
      split: 1,
      maxConnectionPerServer: 1,
      minSplitSizeMib: 16,
      maxTries: 5,
      retryWaitSeconds: 3,
      fileAllocation: 'PREALLOC',
      ipv6Enabled: false,
    },
  });
  readonly jellyfin = new SettingsSlice<JellyfinDraft>({ enabled: false, url: '', token: '' });
  readonly anyDirty = computed(
    () =>
      this.metadata.dirty() || this.search.dirty() || this.storage.dirty() || this.jellyfin.dirty(),
  );

  async load(): Promise<void> {
    this.loading.set(true);
    this.loadError.set('');
    try {
      const [settings, addons, badges] = await Promise.all([
        this.api.get<Settings>('/settings'),
        this.api.get<Addons>('/addons'),
        this.api.get<{ streamBadgeJson: string }>('/settings/stream-badges/defaults'),
      ]);
      this.defaultBadges.set(this.pretty(badges.streamBadgeJson));
      this.metadata.initialize({
        locale: toSupportedLanguage(settings.locale) ?? 'pt-BR',
        addon: this.metadataValue(addons.metadata),
      });
      this.search.initialize({
        addons: this.searchValue(addons.search),
        streamBadgeJson: this.pretty(settings.streamBadgeJson ?? badges.streamBadgeJson),
      });
      this.storage.initialize({
        organization: settings.organization,
        transfer: settings.transfer,
        maxConcurrentDownloads: settings.maxConcurrentDownloads,
      });
      this.jellyfin.initialize({
        enabled: settings.jellyfin.enabled,
        url: settings.jellyfin.url ?? '',
        token: settings.jellyfin.token ?? '',
      });
      this.loaded.set(true);
    } catch (error) {
      this.loadError.set(this.response(error).error ?? 'KZ.REQUEST_FAILED');
    } finally {
      this.loading.set(false);
    }
  }

  saveMetadata(): Promise<void> {
    return this.run(this.metadata, async () => {
      const value = clone(this.metadata.draft());
      if (!equal(value.addon, this.metadata.baseline().addon)) {
        const response = await this.api.put<Pick<Addons, 'metadata'>>(
          '/addons/metadata',
          value.addon,
        );
        this.metadata.commit({ addon: this.metadataValue(response.metadata) });
      }
      if (value.locale !== this.metadata.baseline().locale) {
        const settings = await this.api.put<Settings>('/settings', { locale: value.locale });
        this.metadata.commit({ locale: toSupportedLanguage(settings.locale) ?? value.locale });
      }
      const language = toSupportedLanguage(this.metadata.baseline().locale);
      if (language && language !== this.i18n.currentLanguage())
        await firstValueFrom(this.i18n.setLanguage(language));
    });
  }

  saveSearch(): Promise<void> {
    return this.run(this.search, async () => {
      const value = clone(this.search.draft());
      if (!equal(value.addons, this.search.baseline().addons)) {
        const addons = await this.api.put<Addons>('/addons/search', value.addons);
        this.search.commit({ addons: this.searchValue(addons.search) });
      }
      if (value.streamBadgeJson !== this.search.baseline().streamBadgeJson) {
        const settings = await this.api.put<Settings>('/settings', {
          streamBadgeJson: value.streamBadgeJson,
        });
        this.search.commit({
          streamBadgeJson: this.pretty(settings.streamBadgeJson ?? value.streamBadgeJson),
        });
      }
    });
  }

  saveStorage(): Promise<void> {
    return this.run(this.storage, async () => {
      const value = clone(this.storage.draft());
      const settings = await this.api.put<Settings>('/settings', {
        ...(!equal(value.organization, this.storage.baseline().organization)
          ? { organization: value.organization }
          : {}),
        transfer: value.transfer,
        maxConcurrentDownloads: value.maxConcurrentDownloads,
      });
      this.storage.commit({
        organization: settings.organization,
        transfer: settings.transfer,
        maxConcurrentDownloads: settings.maxConcurrentDownloads,
      });
    });
  }

  saveJellyfin(): Promise<void> {
    return this.run(this.jellyfin, async () => {
      const settings = await this.api.put<Settings>('/settings', {
        jellyfin: clone(this.jellyfin.draft()),
      });
      this.jellyfin.commit({
        enabled: settings.jellyfin.enabled,
        url: settings.jellyfin.url ?? '',
        token: settings.jellyfin.token ?? '',
      });
    });
  }

  async run<T extends object>(
    slice: SettingsSlice<T>,
    action: () => Promise<void>,
    success = 'KZ.SAVED',
  ): Promise<void> {
    if (slice.saving()) return;
    slice.saving.set(true);
    slice.errors.set({});
    slice.error.set('');
    slice.message.set('');
    try {
      await action();
      slice.message.set(success);
    } catch (error) {
      const response = this.response(error);
      slice.error.set(response.error ?? 'KZ.REQUEST_FAILED');
      slice.errors.set(response.errors ?? {});
    } finally {
      slice.saving.set(false);
    }
  }

  private response(error: unknown): { error?: string; errors?: Record<string, string> } {
    return (error as { error?: { error?: string; errors?: Record<string, string> } })?.error ?? {};
  }
  private pretty(value: string): string {
    return JSON.stringify(JSON.parse(value), null, 2);
  }
  private metadataValue(addon?: Addon): MetadataDraft['addon'] {
    return { name: addon?.name ?? '', manifestUrl: addon?.manifestUrl ?? '' };
  }
  private searchValue(addons: Addon[]): SearchAddonInput[] {
    return addons.map(({ id, name, manifestUrl, enabled }) => ({ id, name, manifestUrl, enabled }));
  }
}
