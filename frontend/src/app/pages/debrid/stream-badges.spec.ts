import { TestBed } from '@angular/core/testing';
import { fusionColor, StreamBadgesComponent } from './stream-badges.component';

describe('Fusion stream badge presentation', () => {
  it('converts Android ARGB colors and keeps RGB colors', () => {
    expect(fusionColor('#FF858283', 'transparent')).toBe('#858283FF');
    expect(fusionColor('#80112233', 'transparent')).toBe('#11223380');
    expect(fusionColor('#FFFFFF', 'transparent')).toBe('#FFFFFF');
    expect(fusionColor('invalid', 'transparent')).toBe('transparent');
  });
  it('renders styled images and displays badge names when an image fails', () => {
    const fixture = TestBed.createComponent(StreamBadgesComponent);
    fixture.componentRef.setInput('badges', [
      {
        name: 'HEVC',
        imageURL: 'https://example.com/hevc.png',
        tagColor: '#FF112233',
        tagStyle: 'filled and bordered',
        textColor: '#FFFFFF',
        borderColor: '#FF858283',
      },
    ]);
    fixture.detectChanges();
    const image = fixture.nativeElement.querySelector('img') as HTMLImageElement;
    expect(image.alt).toBe('HEVC');
    expect(fixture.componentInstance.background(fixture.componentInstance.badges()[0])).toBe(
      'transparent',
    );
    expect(fixture.componentInstance.border(fixture.componentInstance.badges()[0])).toBe(
      '#858283FF',
    );
    image.dispatchEvent(new Event('error'));
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('img')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('HEVC');
    expect(fixture.componentInstance.background(fixture.componentInstance.badges()[0])).toBe(
      '#112233FF',
    );
  });
});
