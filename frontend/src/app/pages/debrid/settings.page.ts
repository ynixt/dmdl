import { Component, DestroyRef, HostListener, inject, OnInit, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
import { ConfirmDialogModule } from 'primeng/confirmdialog';
import { AppConfirmationService } from '../../core/ui/confirmation.service';
import { SettingsDraftStore, SettingsTab } from './settings-draft.store';
import { SettingsMetadataTabComponent } from './settings-metadata-tab.component';
import { SettingsSearchTabComponent } from './settings-search-tab.component';
import { SettingsStorageTabComponent } from './settings-storage-tab.component';
import { SettingsJellyfinTabComponent } from './settings-jellyfin-tab.component';

@Component({
  selector: 'app-settings',
  imports: [
    ConfirmDialogModule,
    TranslatePipe,
    SettingsMetadataTabComponent,
    SettingsSearchTabComponent,
    SettingsStorageTabComponent,
    SettingsJellyfinTabComponent,
  ],
  providers: [SettingsDraftStore],
  templateUrl: './settings.page.html',
  styleUrls: ['./debrid.scss', './wizard.scss', './settings.scss'],
})
export class SettingsPage implements OnInit {
  readonly store = inject(SettingsDraftStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);
  private readonly confirmations = inject(AppConfirmationService);
  private readonly translate = inject(TranslateService);
  readonly activeTab = signal<SettingsTab>('metadata');
  readonly tabs: { id: SettingsTab; icon: string }[] = [
    { id: 'metadata', icon: 'pi pi-info-circle' },
    { id: 'search', icon: 'pi pi-search' },
    { id: 'storage', icon: 'pi pi-folder' },
    { id: 'jellyfin', icon: 'pi pi-video' },
  ];

  async ngOnInit(): Promise<void> {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe((params) => {
      const value = params.get('tab');
      const tab = this.tabs.find((item) => item.id === value)?.id ?? 'metadata';
      this.activeTab.set(tab);
      if (value !== tab) void this.reflectTab(tab);
    });
    await this.store.load();
  }

  selectTab(tab: SettingsTab): void {
    this.activeTab.set(tab);
    void this.reflectTab(tab);
  }

  onTabKey(event: KeyboardEvent, index: number): void {
    let next: number;
    switch (event.key) {
      case 'ArrowRight':
        next = (index + 1) % this.tabs.length;
        break;
      case 'ArrowLeft':
        next = (index + this.tabs.length - 1) % this.tabs.length;
        break;
      case 'Home':
        next = 0;
        break;
      case 'End':
        next = this.tabs.length - 1;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.selectTab(this.tabs[next].id);
    const list = (event.currentTarget as HTMLElement).closest('[role="tablist"]');
    list?.querySelectorAll<HTMLButtonElement>('[role="tab"]')[next]?.focus();
  }

  canLeave(): Promise<boolean> | boolean {
    if (!this.store.anyDirty()) return true;
    return this.confirmations.confirm({
      header: this.translate.instant('KZ.UNSAVED_CHANGES'),
      message: this.translate.instant('KZ.LEAVE_SETTINGS_CONFIRM'),
      acceptLabel: this.translate.instant('KZ.DISCARD_CHANGES'),
      rejectLabel: this.translate.instant('KZ.KEEP_EDITING'),
      danger: true,
    });
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(event: BeforeUnloadEvent): void {
    if (!this.store.anyDirty()) return;
    event.preventDefault();
    event.returnValue = '';
  }

  private reflectTab(tab: SettingsTab): Promise<boolean> {
    return this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { tab },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
