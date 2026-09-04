# Manual test feeds

Local fixture feed for manually testing the reader on the emulator. Eight articles:

| Article | File | Exercises |
|---|---|---|
| Short article | `articles/short.html` | Below-the-fold no-scroll case |
| Long text article | `articles/long.html` | Tall text-only page, headline collapse/reveal |
| Image-heavy article | `articles/images.html` | Progressive image loads + layout shift (scroll-strand repro) |
| Video article | `articles/video.html` | Inline `<video>`, fullscreen overlay, back-button exit |
| Audio article | `articles/audio.html` | Native `<audio>` controls vs. scroll gestures |
| YouTube embed article | `articles/youtube.html` | Iframe embeds, player gestures vs. article scroll |
| Footnote article | `articles/footnotes.html` | Footnote ref/back-link taps (anchor scroll) |
| Wide table and code | `articles/table-code.html` | Horizontal table/code scroll must keep working |

## Use it

1. Serve this folder (any static server works):
   `python3 -m http.server 8901`
2. In the ReadYou app on the emulator, subscribe to:
   `http://10.0.2.2:8901/feed.xml`
   (`10.0.2.2` is the emulator's loopback to your machine.)
3. Open each article. Every item carries full `content:encoded`, so bodies render
   without fetching; "full content" mode fetches the matching file under `articles/`.

The same file serves every client: subscribe from the emulator via
`10.0.2.2`, or from physical devices on your LAN via your host's LAN IP
(e.g. `http://192.168.2.101:8901/feed.xml`).

Remote media (picsum.photos, Google sample videos, SoundHelix MP3s, YouTube)
needs emulator network access.
