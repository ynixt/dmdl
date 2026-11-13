import { TestBed } from '@angular/core/testing';
import { By } from '@angular/platform-browser';
import { provideTranslateService } from '@ngx-translate/core';
import { DebridApi, Organization } from '../../services/debrid-api.service';
import { DirectoryPickerComponent } from './directory-picker.component';
import { StorageFieldsComponent } from './storage-fields.component';

describe('Storage fields', () => {
  const api = { get: vi.fn() };
  const organization = (): Organization => ({
    revision: 1,
    categories: {
      MOVIE: {
        folderTemplate: '{title}[ {providerId}]',
        destinations: [{ id: 'movie', name: 'Shared', path: '/media/mixed' }],
        defaultDestinationId: 'movie',
      },
      SERIES: {
        folderTemplate: '{title}[ {providerId}]/Season {season:02}',
        destinations: [],
        defaultDestinationId: null,
      },
      ANIME: {
        folderTemplate: '{title}[ {providerId}]/Season {season:02}',
        destinations: [],
        defaultDestinationId: null,
      },
    },
  });

  beforeEach(() => {
    vi.clearAllMocks();
    TestBed.configureTestingModule({
      providers: [provideTranslateService(), { provide: DebridApi, useValue: api }],
    });
  });

  it('keeps folder rows stable while typing and uses explicit add and remove controls', async () => {
    const fixture = TestBed.createComponent(StorageFieldsComponent);
    fixture.componentRef.setInput('organization', organization());
    fixture.detectChanges();
    const page = fixture.componentInstance;
    const input = fixture.nativeElement.querySelector('#storage-path-movie') as HTMLInputElement;
    input.value = '/hdd/mixed';
    input.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(page.organization.categories.MOVIE.destinations).toHaveLength(1);
    expect(fixture.nativeElement.querySelectorAll('input[type=radio]')).toHaveLength(0);
    fixture.debugElement
      .queryAll(By.css('p-button[icon="pi pi-plus"]'))[0]
      .triggerEventHandler('onClick');
    fixture.detectChanges();
    expect(page.organization.categories.MOVIE.destinations).toHaveLength(2);
    const added = page.organization.categories.MOVIE.destinations[1];
    page.remove('MOVIE', page.organization.categories.MOVIE.destinations[0]);
    expect(page.organization.categories.MOVIE.defaultDestinationId).toBe(added.id);
    page.remove('MOVIE', added);
    expect(page.organization.categories.MOVIE.destinations).toEqual([]);
    expect(page.organization.categories.MOVIE.defaultDestinationId).toBeNull();
  });

  it('can assign the same browsed folder to separate categories', () => {
    const fixture = TestBed.createComponent(StorageFieldsComponent);
    fixture.componentRef.setInput('organization', organization());
    const page = fixture.componentInstance;
    page.add('SERIES');
    const series = page.organization.categories.SERIES.destinations[0];
    page.selectPath(series, '/media/mixed');
    expect(series.path).toBe(page.organization.categories.MOVIE.destinations[0].path);
    expect(series.name).toBe('mixed');
    expect(page.organization.categories.ANIME.destinations).toEqual([]);
  });

  it('navigates server folders and emits only an explicit selection', async () => {
    api.get.mockResolvedValue({
      currentPath: '/media',
      parentPath: '/',
      roots: ['/'],
      directories: [{ name: 'Mixed', path: '/media/Mixed' }],
    });
    const picker = TestBed.createComponent(DirectoryPickerComponent).componentInstance;
    picker.path = '/media';
    const selected = vi.fn();
    picker.selected.subscribe(selected);
    await picker.open();
    expect(api.get).toHaveBeenCalledWith('/storage/directories?path=%2Fmedia');
    expect(picker.listing()?.directories).toHaveLength(1);
    expect(selected).not.toHaveBeenCalled();
    api.get.mockResolvedValue({
      currentPath: '/media/Mixed',
      parentPath: '/media',
      roots: ['/'],
      directories: [],
    });
    await picker.load('/media/Mixed');
    picker.select();
    expect(selected).toHaveBeenCalledWith('/media/Mixed');
    expect(picker.visible()).toBe(false);
    api.get.mockRejectedValue({ error: { error: 'KZ.DIRECTORY_UNAVAILABLE' } });
    await picker.load('/invalid');
    picker.select();
    expect(selected).toHaveBeenCalledTimes(1);
    expect(picker.error()).toBe('KZ.DIRECTORY_UNAVAILABLE');
  });
});
