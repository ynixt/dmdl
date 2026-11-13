import { CommonModule } from '@angular/common';
import {
  ChangeDetectorRef,
  Component,
  inject,
  OnInit,
  signal,
  ChangeDetectionStrategy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { TranslatePipe } from '@ngx-translate/core';
import { ButtonModule } from 'primeng/button';
import { InputTextModule } from 'primeng/inputtext';
import { SelectModule } from 'primeng/select';
import { ToggleSwitchModule } from 'primeng/toggleswitch';
import { firstValueFrom } from 'rxjs';
import { I18nService, toSupportedLanguage } from '../../core/i18n/i18n.service';
import { StorageFieldsComponent } from './storage-fields.component';
import { TransferFieldsComponent } from './transfer-fields.component';
import { StreamBadgeFieldsComponent } from './stream-badge-fields.component';
import {
  Addon,
  Addons,
  Category,
  Content,
  DebridApi,
  Settings,
} from '../../services/debrid-api.service';

@Component({
  selector: 'app-settings',
  imports: [
    CommonModule,
    FormsModule,
    TranslatePipe,
    ButtonModule,
    InputTextModule,
    SelectModule,
    ToggleSwitchModule,
    StorageFieldsComponent,
    TransferFieldsComponent,
    StreamBadgeFieldsComponent,
  ],
  templateUrl: './settings.page.html',
  changeDetection: ChangeDetectionStrategy.Default,
  styleUrl: './debrid.scss',
})
export class SettingsPage implements OnInit {
  private readonly api = inject(DebridApi);
  private readonly changeDetector = inject(ChangeDetectorRef);
  private readonly i18n = inject(I18nService);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly message = signal('');
  readonly previewPath = signal('');
  settings: Settings | null = null;
  defaultBadges = '';
  addons: Addons = { search: [] };
  readonly categories: Category[] = ['MOVIE', 'SERIES', 'ANIME'];
  readonly languages = ['pt-BR', 'en-US'];
  metadataUrl = '';
  metadataName = '';
  searchUrl = '';
  searchName = '';
  editId: string | null = null;
  previewCategory: Category = 'MOVIE';
  async ngOnInit(): Promise<void> {
    await this.run(() => this.reload());
  }
  private async reload(): Promise<void> {
    const [settings, addons, badges] = await Promise.all([
      this.api.get<Settings>('/settings'),
      this.api.get<Addons>('/addons'),
      this.api.get<{ streamBadgeJson: string }>('/settings/stream-badges/defaults'),
    ]);
    this.settings = settings;
    this.defaultBadges = JSON.stringify(JSON.parse(badges.streamBadgeJson), null, 2);
    this.settings.streamBadgeJson = settings.streamBadgeJson
      ? JSON.stringify(JSON.parse(settings.streamBadgeJson), null, 2)
      : this.defaultBadges;
    this.settings.locale = toSupportedLanguage(settings.locale) ?? 'pt-BR';
    this.addons = addons;
    this.metadataName = addons.metadata?.name ?? '';
  }
  async save(): Promise<void> {
    const settings = this.settings;
    if (!settings) return;
    await this.run(async () => {
      await this.api.put('/settings', {
        locale: settings.locale,
        streamBadgeJson: settings.streamBadgeJson,
        maxConcurrentDownloads: settings.maxConcurrentDownloads,
        organization: settings.organization,
        transfer: settings.transfer,
        jellyfin: {
          enabled: settings.jellyfin.enabled,
          url: settings.jellyfin.url,
          token: settings.jellyfin.token,
        },
      });
      const language = toSupportedLanguage(settings.locale);
      if (language) await firstValueFrom(this.i18n.setLanguage(language));
      await this.reload();
      this.message.set('KZ.SAVED');
    });
  }
  async saveMetadata(): Promise<void> {
    await this.run(async () => {
      await this.api.put('/addons/metadata', {
        manifestUrl: this.metadataUrl,
        name: this.metadataName,
      });
      this.metadataUrl = '';
      await this.reload();
      this.message.set('KZ.SAVED');
    });
  }
  async saveSearch(): Promise<void> {
    await this.run(async () => {
      const input = {
        manifestUrl: this.searchUrl || undefined,
        name: this.searchName,
        enabled: true,
        position: this.editId
          ? (this.addons.search.find((item) => item.id === this.editId)?.position ?? 0)
          : this.addons.search.length,
      };
      if (this.editId) await this.api.put(`/addons/search/${this.editId}`, input);
      else await this.api.post('/addons/search', input);
      this.searchUrl = '';
      this.searchName = '';
      this.editId = null;
      await this.reload();
    });
  }
  edit(addon: Addon): void {
    this.editId = addon.id;
    this.searchUrl = '';
    this.searchName = addon.name;
  }
  async toggle(addon: Addon): Promise<void> {
    await this.run(async () => {
      await this.api.put(`/addons/search/${addon.id}`, {
        enabled: !addon.enabled,
        position: addon.position,
      });
      await this.reload();
    });
  }
  async reorder(index: number, direction: number): Promise<void> {
    const list = [...this.addons.search];
    const other = index + direction;
    if (other < 0 || other >= list.length) return;
    [list[index], list[other]] = [list[other], list[index]];
    await this.run(async () => {
      for (const [position, addon] of list.entries())
        await this.api.put(`/addons/search/${addon.id}`, {
          enabled: addon.enabled,
          position,
        });
      await this.reload();
    });
  }
  async removeAddon(addon: Addon): Promise<void> {
    await this.run(async () => {
      await this.api.delete(`/addons/search/${addon.id}`);
      await this.reload();
    });
  }
  async restore(): Promise<void> {
    await this.run(async () => {
      const defaults = await this.api.get<Settings['organization']>(
        '/settings/organization/defaults',
      );
      if (this.settings) this.settings.organization = defaults;
      this.previewPath.set('');
    });
  }
  async preview(): Promise<void> {
    if (!this.settings) return;
    const episodic = this.previewCategory !== 'MOVIE';
    const content: Content = {
      identity: {
        sourceId: '00000000-0000-0000-0000-000000000001',
        sourceRevision: 1,
        resourceType: episodic ? 'series' : 'movie',
        contentId: 'preview',
        ...(episodic ? { videoId: 'preview:0:1' } : {}),
      },
      title: 'Example',
      year: 2024,
      category: this.previewCategory,
      ...(episodic ? { season: 0, episode: 1, episodeTitle: 'Arrival' } : {}),
    };
    await this.run(async () =>
      this.previewPath.set(
        (
          await this.api.post<{ path: string }>('/settings/organization/preview', {
            organization: this.settings!.organization,
            content,
            originalFileName: 'Example.Release.1080p.WEB-DL.H264-GROUP.mkv',
          })
        ).path,
      ),
    );
  }
  async testJellyfin(): Promise<void> {
    if (!this.settings) return;
    await this.run(async () => {
      await this.api.post('/settings/jellyfin/test', {
        enabled: this.settings!.jellyfin.enabled,
        url: this.settings!.jellyfin.url,
        token: this.settings!.jellyfin.token,
      });
      this.message.set('KZ.CONNECTION_OK');
    });
  }
  private async run(action: () => Promise<void>): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    this.message.set('');
    try {
      await action();
    } catch (error) {
      this.error.set(
        (error as { error?: { error?: string } })?.error?.error ?? 'KZ.REQUEST_FAILED',
      );
    } finally {
      this.busy.set(false);
      this.changeDetector.markForCheck();
    }
  }
}
