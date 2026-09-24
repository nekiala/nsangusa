export const sessionChangedEvent = "nsangusa:session-changed";

export function notifySessionChanged(reset = true, ending = false) {
  if (typeof window !== "undefined") window.dispatchEvent(new CustomEvent(sessionChangedEvent, { detail: { reset, ending } }));
}
