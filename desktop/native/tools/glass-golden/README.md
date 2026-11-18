# Glass golden check

The native Liquid Glass must look like the Tauri one. This renders the Tauri glass (the CSS of
`app/src/lib/glass/Glass.svelte` and the SVG lens of `lens.ts`, copied into `glass.html`) with
Chromium, the engine of WebView2, over a screenshot of the native window without the player,
and diffs it against the native window with an empty glass player.

1. Chromium: `npx @puppeteer/browsers install chrome-headless-shell@stable --path <dir>` (if it
   stops half-way, unzip the downloaded zip by hand).
2. Native screenshots (KDE `spectacle`; any tool that captures the active window works):

       LFLY_GLASS_HIDE=1  LFLY_GLASS_DEBUG=1 LFLY_MUTE=1 LFLY_AUTOPLAY="Daft Punk Get Lucky" target/release/lfly   # -> backdrop.png
       LFLY_GLASS_EMPTY=1 LFLY_GLASS_DEBUG=1 LFLY_MUTE=1 LFLY_AUTOPLAY="Daft Punk Get Lucky" target/release/lfly   # -> native.png

   (`spectacle -b -n -a -o <file>` about 12 s after start.) Measure where the native glass
   sits (the window has a 20 px client-side margin) and set the same box in `glass.html`.
3. Reference: copy `backdrop.png` next to `glass.html`, then

       chrome-headless-shell --no-sandbox --hide-scrollbars --force-device-scale-factor=1 \
         --window-size=1100,720 --virtual-time-budget=3000 --screenshot=reference.png file://$PWD/glass.html

4. `python3 compare.py reference.png native.png`

First result (2026-11-12, dark scheme, strength 0.75, 1036x76 pill): mean 0.71/255, 0.2% of
pixels off by more than 12. Still to cover: small pills, circles, radius-30 rects, hover and
pressed, `clear`/`tinted`, the light scheme and lens off (set `const value` in `glass.html` to
`blur(26px) saturate(1.525)` and run the native app with the lens off).
