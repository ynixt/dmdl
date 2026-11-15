import en from '../../../../public/i18n/en-US.json';
import pt from '../../../../public/i18n/pt-BR.json';

describe('Addon validation error translations', () => {
  const dictionaries = { 'en-US': en.KZ, 'pt-BR': pt.KZ } as Record<string, Record<string, string>>;

  it.each(Object.keys(dictionaries))('%s names each missing required resource', (language) => {
    const kz = dictionaries[language];
    const missing: Record<string, string[]> = {
      ADDON_MISSING_CATALOG: ['"catalog"'],
      ADDON_MISSING_META: ['"meta"'],
      ADDON_MISSING_CATALOG_META: ['"catalog"', '"meta"'],
      ADDON_MISSING_STREAM: ['"stream"'],
    };
    for (const [code, resources] of Object.entries(missing)) {
      for (const resource of resources) expect(kz[code], code).toContain(resource);
    }
    expect(kz['ADDON_MISSING_META']).not.toContain('"catalog"');
    expect(kz['ADDON_MISSING_CATALOG']).not.toContain('"meta"');
  });

  it.each(Object.keys(dictionaries))(
    '%s keeps distinct transport and parser explanations',
    (language) => {
      const kz = dictionaries[language];
      const codes = [
        'ADDON_INVALID_URL',
        'ADDON_UNAVAILABLE',
        'ADDON_TIMEOUT',
        'ADDON_UNAUTHORIZED',
        'ADDON_NOT_FOUND',
        'ADDON_HTTP_ERROR',
        'ADDON_INVALID_MANIFEST',
        'ADDON_MISSING_CATALOG',
        'ADDON_MISSING_META',
        'ADDON_MISSING_CATALOG_META',
        'ADDON_MISSING_STREAM',
      ];
      const messages = codes.map((code) => kz[code]);
      messages.forEach((message, index) => expect(message, codes[index]).toBeTruthy());
      expect(new Set(messages).size).toBe(codes.length);
    },
  );

  it('defines the same addon codes in both languages', () => {
    const addonKeys = (kz: Record<string, string>) =>
      Object.keys(kz)
        .filter((key) => key.startsWith('ADDON_'))
        .sort();
    expect(addonKeys(dictionaries['pt-BR'])).toEqual(addonKeys(dictionaries['en-US']));
  });
});
