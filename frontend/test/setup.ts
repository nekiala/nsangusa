import "@testing-library/jest-dom/vitest";

// Node 25+ defines its own `localStorage` global, which is undefined without --localstorage-file
// and shadows the one jsdom provides. Tests must see the browser storage they would in a page.
const dom = (globalThis as { jsdom?: { window: Window } }).jsdom;
if (dom && typeof localStorage === "undefined") {
  Object.defineProperty(globalThis, "localStorage", { value: dom.window.localStorage, configurable: true });
}
