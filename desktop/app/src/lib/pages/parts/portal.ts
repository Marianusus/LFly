// Moves an element to the end of <body>, so a full-window view is never clipped or
// repositioned by the scrolling page it is declared in.
export function portal(node: HTMLElement) {
  document.body.appendChild(node);
  return {
    destroy() {
      node.remove();
    },
  };
}
