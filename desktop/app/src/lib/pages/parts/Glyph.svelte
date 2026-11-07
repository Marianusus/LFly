<script lang="ts">
  // The few glyphs the pages need beyond the shared icon set, drawn at the same weight.
  let { name, size = 18, fill = false }: { name: string; size?: number; fill?: boolean } = $props();

  const paths: Record<string, string> = {
    "chevron-down": "M5.5 9l6.5 6.5L18.5 9",
    "chevron-right": "M9 5.5l6.5 6.5L9 18.5",
    "chevron-left": "M15 5.5L8.5 12l6.5 6.5",
    "volume-low": "M4 9.5v5h3.5L12 19V5L7.5 9.5H4ZM15.5 9a4 4 0 0 1 0 6",
    "volume-high": "M4 9.5v5h3.5L12 19V5L7.5 9.5H4ZM15.5 8.5a5 5 0 0 1 0 7M18 6a8.5 8.5 0 0 1 0 12",
    output: "M6.5 17.5 12 12l5.5 5.5ZM8 15.5H5a1.5 1.5 0 0 1-1.5-1.5V5A1.5 1.5 0 0 1 5 3.5h14A1.5 1.5 0 0 1 20.5 5v9a1.5 1.5 0 0 1-1.5 1.5h-3",
    grid: "M4 4h6.5v6.5H4zM13.5 4H20v6.5h-6.5zM4 13.5h6.5V20H4zM13.5 13.5H20V20h-6.5z",
    list: "M8.5 6h12M8.5 12h12M8.5 18h12M4 6h.01M4 12h.01M4 18h.01",
    history: "M3.5 12a8.5 8.5 0 1 0 2.5-6L3.5 8.5M3.5 4v4.5H8M12 7.5V12l3 2",
    person: "M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8ZM4.5 20.5c.8-3.6 3.9-6 7.5-6s6.7 2.4 7.5 6",
    sparkle: "M12 3l1.9 5.6L19.5 10l-5.6 1.9L12 17.5l-1.9-5.6L4.5 10l5.6-1.4ZM18.5 15.5l.8 2.2 2.2.8-2.2.8-.8 2.2-.8-2.2-2.2-.8 2.2-.8Z",
    expand: "M14 4h6v6M10 20H4v-6M20 4l-7 7M4 20l7-7",
    disc: "M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18ZM12 14.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5Z",
    video: "M3.5 6.5h12v11h-12zM15.5 10.5l5-3v9l-5-3",
  };
</script>

<svg
  width={size}
  height={size}
  viewBox="0 0 24 24"
  aria-hidden="true"
  fill={fill ? "currentColor" : "none"}
  stroke="currentColor"
  stroke-width="1.8"
  stroke-linecap="round"
  stroke-linejoin="round"
>
  <path d={paths[name] ?? ""} />
</svg>
