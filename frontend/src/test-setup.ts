// Node 25+ defines a global `localStorage`/`sessionStorage` accessor that returns undefined
// unless `--localstorage-file` is passed. It shadows the jsdom storage that Vitest copies onto
// the global scope, so restore jsdom's implementation when Node's is not usable.
declare const jsdom: { window: Window } | undefined;

for (const name of ['localStorage', 'sessionStorage'] as const) {
  if (globalThis[name] === undefined && typeof jsdom !== 'undefined') {
    Object.defineProperty(globalThis, name, {
      value: jsdom.window[name],
      configurable: true,
      writable: true,
    });
  }
}
