# Journal Scrape

A client-side Fabric mod for **Minecraft 26.2** that walks a server's `/journal` knowledge GUI,
dumps every entry to JSON, and links the file in chat.

Run `/journal` and it takes over: it normalises to the first tab and first page, then clicks each
entry, reads every page of the dialog that opens, pages through the whole tab, and writes the
result. `/journal 5` scrapes only the first five entries.

**It survives a kick.** If the connection drops mid-walk - including because the server kicked you
for the very commands this mod sends - the run is not lost: progress is written out immediately,
the walk parks, and it resumes at the entry it was on once you are back in the world. There is
no cancel command: the GUI is open for the whole run, so there would never be a moment to type
one - disconnect or close the game if you need a run to stop. The output
records `disconnects` and a `complete` flag, and partial results are saved every 10 entries anyway,
so even a crash leaves a usable file.

**No command throttle.** Reading an entry closes the grid, so the walk re-issues `/journal` once
per entry, as fast as it can - which a server may treat as command spam. That is accepted rather
than prevented: a kick pauses the walk instead of ending it, and rejoining is usually quicker than
pacing every reopen would have been. Reopening the journal returns you to the page you
were on, so a resume normally continues straight from the entry it was interrupted at, without
re-navigating.

## What it captures

Per entry: name, container slot, and every page - title, body text, and any **drops**. The drop
names carry `ShowItem` hovers containing the server's own loot definition, which is kept verbatim
as `lootData`:

```json
{"name": "Ancient Axe",
 "lootData": "{\"stringData\":{\"name\":\"Ancient Axe\",\"tier\":\"axe\",\"rarity\":\"RARE\"},
               \"intData\":{\"level\":68,\"lockedSockets\":3},
               \"coreStats\":[{\"statId\":\"AXE-MELEE\",\"cachedValue\":37,\"statRoll\":100}, ...]}"}
```

So a scrape yields not just "this mob drops an Ancient Axe" but its tier, rarity, level, socket
count, enchantability, and every stat with its roll. Materials carry `"kind": "material"` instead.

**It can only capture what you have unlocked.** Locked pages read `Reach knowledge level N to
unlock!` and are recorded as such rather than skipped, so the output shows the gaps honestly.

## Requirements

Minecraft `~26.2`, Fabric Loader `>=0.19.0`, Java `>=25`. No Fabric API, no other mods. Client-only
(`"environment": "client"`), so nothing is needed server-side.

## How it works, and the traps

The grid is a container screen; clicking an entry opens a **vanilla server dialog**
(`MultiActionDialog`), so page content is structured data rather than rendered text - the mod reads
the record, not the pixels.

Every page turn is a server round trip, and a mod cannot sleep without freezing the client the
reply arrives on. The walk is therefore a state machine advanced once per client tick.

Three things that cost a debugging round each, and will again in any rewrite:

* a dialog button's `active` flag is **always true** - the end of the pages is the button's
  `action()` being empty
* `Style` accessors are `getHoverEvent()` / `getClickEvent()`, not the record-style names
* closing a dialog does not reliably return you to the grid, so the command is re-issued - and
  because that send passes through this mod's own hook, a running scrape must refuse to re-arm
* grid pages OVERLAP BY ONE ENTRY, so the stride is 31 rather than 32, and entries must be keyed by
  name AND level - duplicate names are legitimate, the same mob appearing at two enemy levels
* the journal may reopen on a different page than the one being walked, so every reopen checks a
  page fingerprint and navigates back when it does not match

`mc.screen` does not exist in 26.2: the current screen is `mc.gui.screen()`.

## Building

```sh
export JAVA_HOME=/path/to/jdk-25
./gradlew build          # -> build/libs/journalscrape-1.0.0.jar
```

No local game install needed - the compile classpath is fetched from Mojang's piston metadata and
sha1-verified, then cached under `build/minecraft/26.2/`.

## Status

Working, with caveats. A run against a live journal captured the first page of entries and their
drop payloads correctly. Since then the walk gained grid pagination, overlap de-duplication and the
tab/page normalisation, and **those additions have not yet been exercised end to end** - test with
`/journal 40`, which is enough to cross a page boundary, before trusting a full run.

Only the FIRST tab is walked; the other categories are not covered yet.

## License

MIT.
