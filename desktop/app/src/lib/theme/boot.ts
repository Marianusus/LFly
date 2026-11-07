// The look of the last session, put on the page before anything is drawn: the window opens in its
// own colors instead of flashing the default dark ones, and nothing animates while it settles.

const KEY = "lfly-look";

export function bootLook() {
  const root = document.documentElement;
  root.dataset.lflyBoot = "";
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? "null") as { vars: Record<string, string>; data: Record<string, string> } | null;
    if (saved?.vars) for (const [k, v] of Object.entries(saved.vars)) root.style.setProperty(k, v);
    if (saved?.data) Object.assign(root.dataset, saved.data);
    if (saved?.data?.scheme) root.style.colorScheme = saved.data.scheme;
  } catch {
    // No saved look: the defaults in app.css.
  }
}

/** Remembers what [applyLook] put on the page. */
export function rememberLook() {
  const root = document.documentElement;
  const vars: Record<string, string> = {};
  for (let i = 0; i < root.style.length; i++) {
    const k = root.style[i];
    if (k.startsWith("--lfly-") || k.startsWith("--spice-")) vars[k] = root.style.getPropertyValue(k);
  }
  const { glass = "", lens = "", scheme = "", mode = "" } = root.dataset;
  try {
    localStorage.setItem(KEY, JSON.stringify({ vars, data: { glass, lens, scheme, mode } }));
  } catch {
    // Storage full or blocked: the next start shows the defaults first.
  }
}

/** Lets transitions run again once the first look is on screen. */
export function endBoot() {
  requestAnimationFrame(() => requestAnimationFrame(() => setTimeout(() => delete document.documentElement.dataset.lflyBoot, 400)));
}
