// Where the window is, with back and forward like a browser.

export type Route =
  | { page: "home" }
  | { page: "search"; query?: string }
  | { page: "library" }
  | { page: "liked" }
  | { page: "downloads" }
  | { page: "artist"; id: string; name?: string }
  | { page: "album"; id: string; name?: string }
  | { page: "playlist"; id: string; name?: string; local?: boolean }
  | { page: "settings"; section?: "appearance" | "sound" | "devices" | "account" | "about" }
  | { page: "lyrics" }
  | { page: "nowplaying" };

/** A page named in the address (#settings/appearance), for development and deep links. */
function initial(): Route {
  const [page, arg] = (typeof location === "undefined" ? "" : location.hash.slice(1)).split("/");
  if (page === "settings") return { page: "settings", section: (arg as "appearance") || "appearance" };
  if (page === "search") return { page: "search", query: arg ? decodeURIComponent(arg) : "" };
  if (page === "library" || page === "liked" || page === "lyrics" || page === "downloads" || page === "nowplaying") return { page };
  return { page: "home" };
}

class Nav {
  private stack = $state<Route[]>([initial()]);
  private index = $state(0);

  route = $derived(this.stack[this.index]);
  canBack = $derived(this.index > 0);
  canForward = $derived(this.index < this.stack.length - 1);

  go(route: Route) {
    if (JSON.stringify(route) === JSON.stringify(this.route)) return;
    this.stack = [...this.stack.slice(0, this.index + 1), route].slice(-50);
    this.index = this.stack.length - 1;
  }

  /** Same page with new details (a search being typed): no new history step. */
  replace(route: Route) {
    this.stack[this.index] = route;
  }

  back() {
    if (this.canBack) this.index--;
  }

  forward() {
    if (this.canForward) this.index++;
  }
}

export const nav = new Nav();
