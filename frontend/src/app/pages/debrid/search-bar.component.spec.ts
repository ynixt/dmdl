import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';
import { Catalog } from '../../services/debrid-api.service';
import { SearchBarComponent } from './search-bar.component';

describe('SearchBarComponent', () => {
  const catalog: Catalog = {
    id: 'search',
    type: 'movie',
    name: 'Movies',
    searchable: true,
    browsable: false,
    pageable: false,
  };

  beforeEach(() => TestBed.configureTestingModule({ providers: [provideTranslateService()] }));

  it('lists searchable catalogs, restores initial values and emits trimmed searches', async () => {
    const fixture = TestBed.createComponent(SearchBarComponent);
    fixture.componentRef.setInput('catalogs', [
      { ...catalog, id: 'browse', searchable: false },
      catalog,
    ]);
    fixture.componentRef.setInput('initialCatalog', catalog);
    fixture.componentRef.setInput('initialQuery', ' dune ');
    fixture.detectChanges();
    await fixture.whenStable();
    expect(fixture.componentInstance.searchableCatalogs).toEqual([catalog]);
    expect(fixture.nativeElement.querySelector('#query').value).toBe(' dune ');
    const emitted = vi.fn();
    fixture.componentInstance.submitted.subscribe(emitted);
    fixture.componentInstance.submit();
    expect(emitted).toHaveBeenCalledWith({ catalog, query: 'dune' });
  });

  it('disables submission for blank titles, missing catalogs and an active search', async () => {
    const fixture = TestBed.createComponent(SearchBarComponent);
    fixture.componentRef.setInput('catalogs', [catalog]);
    fixture.componentRef.setInput('initialQuery', '   ');
    fixture.detectChanges();
    await fixture.whenStable();
    const emitted = vi.fn();
    fixture.componentInstance.submitted.subscribe(emitted);
    expect(fixture.nativeElement.querySelector('button').disabled).toBe(true);
    fixture.nativeElement.querySelector('form').dispatchEvent(new Event('submit'));
    expect(emitted).not.toHaveBeenCalled();
    fixture.componentInstance.query = 'dune';
    fixture.componentInstance.catalog = null;
    fixture.componentInstance.submit();
    fixture.componentInstance.catalog = catalog;
    fixture.componentRef.setInput('busy', true);
    fixture.componentInstance.submit();
    expect(emitted).not.toHaveBeenCalled();
  });
});
