import { mount } from "svelte";
import "./app.css";
import { bootLook } from "./lib/theme/boot";
import App from "./App.svelte";

// The saved look first, so the window opens in its own colors.
bootLook();

// If the window fails to start, say so instead of staying blank.
function showFailure(message: string) {
  const root = document.getElementById("app");
  if (!root || root.querySelector(".lfly-shell-ok")) return;
  root.innerHTML = "";
  const box = document.createElement("pre");
  box.style.cssText =
    "margin:24px;padding:16px;white-space:pre-wrap;font:12px ui-monospace,monospace;color:#ffb4c4;background:#2a1017;border-radius:8px;user-select:text";
  box.textContent = `LFly non è riuscito ad avviare la finestra:\n\n${message}`;
  root.appendChild(box);
}

window.addEventListener("error", (e) => showFailure(`${e.message}\n${e.error?.stack ?? ""}`));
window.addEventListener("unhandledrejection", (e) => console.warn("Unhandled:", e.reason));

try {
  mount(App, { target: document.getElementById("app")! });
} catch (e) {
  showFailure(e instanceof Error ? `${e.message}\n${e.stack ?? ""}` : String(e));
}
