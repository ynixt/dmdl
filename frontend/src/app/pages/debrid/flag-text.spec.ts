import { TestBed } from '@angular/core/testing';
import { FlagTextComponent, flagParts } from './flag-text.component';

describe('Country flags in stream text', () => {
  it('extracts country and regional flags while preserving subtitles markers and separators', () => {
    const text =
      '🇬🇧 / 🇧🇷 Subs / 🇵🇹 / 🇧🇬 / 🇨🇳 / 🇨🇿 / 🇩🇰 / 🇩🇪 / 🇬🇷 / 💃🏻 / 🇪🇸 / 🇪🇪 / 🇫🇮 / 🇫🇷 / 🇮🇱 / 🇭🇷 / 🇭🇺 / 🇮🇩 / 🇮🇹 / 🇯🇵 / 🇰🇷 / 🇱🇹 / 🇱🇻 / 🇳🇱 / 🇳🇴 / 🇵🇱 / 🇷🇴 / 🇸🇰 / 🇸🇮 / 🇷🇸 / 🇸🇪 / 🇹🇭 / 🇹🇷 / 🇻🇳';
    const parts = flagParts(text);
    expect(parts.filter((part) => part.country)).toHaveLength(34);
    expect(parts).toContainEqual({ text: 'LATAM', country: 'latam' });
    expect(flagParts('💃 / 💃🏿').filter((part) => part.country === 'latam')).toHaveLength(2);
    expect(parts.some((part) => part.text.includes('Subs'))).toBe(true);
    expect(flagParts('Original.Release.H265.mkv')).toEqual([{ text: 'Original.Release.H265.mkv' }]);
  });
  it('uses local images and falls back to a country code on image error', () => {
    const fixture = TestBed.createComponent(FlagTextComponent);
    fixture.componentRef.setInput('text', '🇧🇷 Subs / 🇬🇧 / 💃🏻');
    fixture.detectChanges();
    const images = fixture.nativeElement.querySelectorAll('img') as NodeListOf<HTMLImageElement>;
    expect(images).toHaveLength(3);
    expect(images[0].getAttribute('src')).toBe('/flags/br.svg');
    expect(fixture.nativeElement.textContent).toContain('Subs');
    expect(images[2].getAttribute('src')).toBe('/flags/latam.svg');
    expect(fixture.nativeElement.textContent).toContain('LATAM');
    expect(fixture.nativeElement.textContent).not.toContain('💃');
    images[0].dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('BR');
  });
});
