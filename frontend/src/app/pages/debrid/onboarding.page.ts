import { Component, ChangeDetectorRef, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { firstValueFrom } from 'rxjs';
import { SecretInputComponent } from '../../components/secret-input/secret-input.component';
import { AuthService } from '../../core/auth/auth.service';
import { I18nService, toSupportedLanguage } from '../../core/i18n/i18n.service';
import {
  Category,
  Content,
  DebridApi,
  OnboardingDraft,
  Settings,
  SetupSearch,
  SetupValidation,
} from '../../services/debrid-api.service';
import { StorageFieldsComponent } from './storage-fields.component';
import { TransferFieldsComponent } from './transfer-fields.component';
import { StreamBadgeFieldsComponent } from './stream-badge-fields.component';

@Component({
  selector: 'app-onboarding',
  imports: [
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
    StorageFieldsComponent,
    TransferFieldsComponent,
    StreamBadgeFieldsComponent,
    SecretInputComponent,
  ],
  templateUrl: './onboarding.page.html',
  styleUrls: ['./debrid.scss', './wizard.scss'],
})
export class OnboardingPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly i18n = inject(I18nService);
  private readonly changeDetector = inject(ChangeDetectorRef);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly errors = signal<Record<string, string>>({});
  readonly step = signal(0);
  readonly previews = signal<Record<string, string>>({});
  readonly testingConnection = signal<'JELLYFIN' | null>(null);
  readonly connectionTests = signal<Record<string, { ok: boolean; message: string }>>({});
  private previewRevision = 0;
  readonly steps = ['metadata', 'search', 'storage', 'jellyfin'];
  readonly languages = ['pt-BR', 'en-US'];
  searchRows: SetupSearch[] = [this.emptySource()];
  draft: OnboardingDraft | null = null;
  revision = 0;
  defaultBadges = '';
  private submissionId = crypto.randomUUID();
  private submittedPayload = '';

  async ngOnInit(): Promise<void> {
    await this.run(async () => {
      const [settings, badges] = await Promise.all([
        this.api.get<Settings>('/settings'),
        this.api.get<{ streamBadgeJson: string }>('/settings/stream-badges/defaults'),
      ]);
      if (settings.onboardingCompleted) {
        await this.auth.refreshSession();
        await this.router.navigate(['/discovery']);
        return;
      }
      this.revision = settings.configurationRevision;
      this.defaultBadges = JSON.stringify(JSON.parse(badges.streamBadgeJson), null, 2);
      this.draft = {
        settings: {
          locale: this.i18n.currentLanguage(),
          streamBadgeJson: settings.streamBadgeJson
            ? JSON.stringify(JSON.parse(settings.streamBadgeJson), null, 2)
            : this.defaultBadges,
          maxConcurrentDownloads: settings.maxConcurrentDownloads,
          organization: settings.organization,
          transfer: settings.transfer,
          jellyfin: {
            enabled: settings.jellyfin.enabled,
            url: settings.jellyfin.url ?? '',
            token: settings.jellyfin.token ?? '',
          },
        },
        metadataUrl: '',
        metadataName: '',
        search: [],
      };
    });
  }

  clearConnectionTest(key: 'JELLYFIN'): void {
    this.connectionTests.update((current) => {
      const updated = { ...current };
      delete updated[key];
      return updated;
    });
  }

  async testJellyfin(): Promise<void> {
    const settings = this.draft?.settings.jellyfin;
    if (!settings?.enabled || !settings.url?.trim() || !settings.token?.trim()) return;
    await this.testConnection('JELLYFIN', '/settings/jellyfin/test', {
      enabled: true,
      url: settings.url.trim(),
      token: settings.token.trim(),
    });
  }

  private async testConnection(key: 'JELLYFIN', path: string, body: unknown): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.testingConnection.set(key);
    this.clearConnectionTest(key);
    try {
      const result = await this.api.post<{ ok: boolean }>(path, body);
      if (!result.ok)
        throw {
          error: {
            error: 'KZ.REQUEST_FAILED',
          },
        };
      this.connectionTests.update((current) => ({
        ...current,
        [key]: { ok: true, message: 'KZ.CONNECTION_OK' },
      }));
    } catch (error) {
      this.connectionTests.update((current) => ({
        ...current,
        [key]: {
          ok: false,
          message: (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
        },
      }));
    } finally {
      this.testingConnection.set(null);
      this.busy.set(false);
      this.changeDetector.markForCheck();
    }
  }

  async changeLanguage(locale: string): Promise<void> {
    const language = toSupportedLanguage(locale);
    if (!language || !this.draft) return;
    this.draft.settings.locale = language;
    try {
      await firstValueFrom(this.i18n.setLanguage(language));
    } catch {
      this.error.set('KZ.REQUEST_FAILED');
    } finally {
      this.changeDetector.markForCheck();
    }
  }

  addSource(): void {
    this.searchRows.push(this.emptySource());
  }

  removeSource(index: number): void {
    this.searchRows.splice(index, 1);
    this.errors.set({});
  }

  private emptySource(): SetupSearch {
    return { name: '', manifestUrl: '', enabled: true };
  }

  payload(): OnboardingDraft {
    const draft = this.draft!;
    return {
      ...draft,
      settings: { ...draft.settings, organization: draft.settings.organization },
      search: this.searchRows,
    };
  }

  async advance(): Promise<void> {
    if (this.busy() || !this.draft) return;
    await this.run(async () => {
      const result = await this.api.post<SetupValidation>('/setup/validate', {
        step: this.steps[this.step()],
        draft: this.payload(),
      });
      this.errors.set(result.errors);
      if (!result.valid) return;
      this.step.update((step) => step + 1);
      if (this.step() === 2) await this.refreshPreviews();
      window.scrollTo({ top: 0, behavior: 'smooth' });
    });
  }

  async complete(): Promise<void> {
    if (this.busy() || !this.draft) return;
    await this.run(async () => {
      const draft = this.payload();
      const fingerprint = JSON.stringify(draft);
      if (this.submittedPayload && this.submittedPayload !== fingerprint)
        this.submissionId = crypto.randomUUID();
      this.submittedPayload = fingerprint;
      await this.api.post('/setup/complete', {
        draft,
        expectedRevision: this.revision,
        submissionId: this.submissionId,
      });
      await this.auth.refreshSession();
      await this.router.navigate(['/discovery']);
    });
  }

  errorEntries(): [string, string][] {
    return Object.entries(this.errors()).filter(
      ([field]) => field !== 'metadata.manifestUrl' && !/^search\.\d+\.manifestUrl$/.test(field),
    );
  }

  async refreshPreviews(): Promise<void> {
    if (!this.draft) return;
    const revision = ++this.previewRevision;
    const organization = this.draft.settings.organization;
    const categories: Category[] = ['MOVIE', 'SERIES', 'ANIME'];
    const paths: Record<string, string> = {};
    await Promise.all(
      categories.map(async (category) => {
        if (!organization.categories[category].destinations.length) return;
        const episodic = category !== 'MOVIE';
        const content: Content = {
          identity: {
            sourceId: '00000000-0000-0000-0000-000000000001',
            sourceRevision: 1,
            resourceType: episodic ? 'series' : 'movie',
            contentId: 'preview',
            mappedIds: { tvdb: '81189', tmdb: '1396' },
            ...(episodic ? { videoId: 'preview:1:1' } : {}),
          },
          category,
          title: 'Example',
          year: 2024,
          ...(episodic ? { season: 1, episode: 1 } : {}),
        };
        try {
          const result = await this.api.post<{ path: string }>('/settings/organization/preview', {
            organization,
            content,
            originalFileName: 'Example.Release.2160p.H265.HDR.Atmos.mkv',
          });
          if (result.path) paths[category] = result.path;
        } catch {
          /* Invalid draft fields are reported by step validation. */
        }
      }),
    );
    if (revision === this.previewRevision) this.previews.set(paths);
    this.changeDetector.markForCheck();
  }

  errorLabel(field: string): string {
    if (field.startsWith('storage.')) return 'KZ.CATEGORY_' + field.split('.')[1];
    if (field.startsWith('metadata')) return 'KZ.METADATA_ADDON';
    if (field.startsWith('search')) return 'KZ.SEARCH_ADDONS';
    return field === 'jellyfin' ? 'KZ.JELLYFIN_OPTIONAL' : 'KZ.ORGANIZATION';
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    this.errors.set({});
    try {
      await action();
    } catch (error) {
      const response = (error as { error?: { error?: string; errors?: Record<string, string> } })
        .error;
      this.error.set(response?.error ?? 'KZ.REQUEST_FAILED');
      const errors = response?.errors ?? {};
      this.errors.set(errors);
      const firstField = Object.keys(errors)[0]?.split('.')[0];
      const targetStep = this.steps.indexOf(firstField ?? '');
      if (targetStep >= 0) this.step.set(targetStep);
    } finally {
      this.busy.set(false);
      this.changeDetector.markForCheck();
    }
  }
}
