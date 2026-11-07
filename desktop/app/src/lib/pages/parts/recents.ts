// The searches and results opened lately, kept in this window's storage (a convenience: losing
// them loses nothing). Same idea as the phone's search history.
import type { Item } from "../../api";

export type Recent = { query: string } | { item: Item };
const KEY = "lfly.search.recent";

export function loadRecents(): Recent[] {
  try {
    const v = JSON.parse(localStorage.getItem(KEY) ?? "[]");
    return Array.isArray(v) ? v.slice(0, 12) : [];
  } catch {
    return [];
  }
}

function save(list: Recent[]) {
  try {
    localStorage.setItem(KEY, JSON.stringify(list.slice(0, 12)));
  } catch {
    // Storage off: the list just isn't kept.
  }
}

const same = (a: Recent, b: Recent) =>
  "query" in a && "query" in b
    ? a.query.toLowerCase() === b.query.toLowerCase()
    : "item" in a && "item" in b && a.item.kind === b.item.kind && a.item.id === b.item.id;

export function addRecent(list: Recent[], r: Recent): Recent[] {
  if ("query" in r && !r.query.trim()) return list;
  const next = [r, ...list.filter((x) => !same(x, r))].slice(0, 12);
  save(next);
  return next;
}

export function removeRecent(list: Recent[], r: Recent): Recent[] {
  const next = list.filter((x) => !same(x, r));
  save(next);
  return next;
}

export function clearRecents(): Recent[] {
  save([]);
  return [];
}
