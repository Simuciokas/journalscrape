# Journal Scrape

A client-side Fabric mod for **Minecraft 26.2** that walks a server's `/journal` knowledge GUI,
dumps every entry to JSON, and links the file in chat.

Run `/journal` and it takes over: it walks every category tab, and every page of each - clicking
each entry, reading every page of the dialog that opens - then writes the result. `/journal 5`
scrapes only the first five entries; `/journal force` re-reads everything, ignoring every shortcut
described below.

The tab list is read off the top row rather than hardcoded, so empty slots are skipped and a server
that adds a category is picked up automatically. Slot 8 is deliberately never clicked: it is the
Region Filter, and pressing it would change what the journal shows rather than move between tabs.

**It survives a kick.** If the connection drops mid-walk - including because the server kicked you
for the very commands this mod sends - the run is not lost: progress is written out immediately,
the walk parks, and it resumes at the entry it was on once you are back in the world. There is
no cancel command: the GUI is open for the whole run, so there would never be a moment to type
one - disconnect or close the game if you need a run to stop. The output records `disconnects` and
a `complete` flag, and partial results are saved every 10 entries anyway, so even a crash leaves a
usable file.

**No command throttle.** Reading an entry closes the grid, so the walk re-issues `/journal` once
per entry, as fast as it can - which a server may treat as command spam. That is accepted rather
than prevented: a kick pauses the walk instead of ending it, and rejoining is usually quicker than
pacing every reopen would have been. Reopening the journal returns you to the page you were on, so
a resume normally continues straight from the entry it was interrupted at, without re-navigating.

## It only opens what it might learn from

Opening an entry is the expensive part - a dialog round trip per page, plus a `/journal` to get the
grid back. The walk therefore checks two things first, and where neither can teach it anything the
entry is walked past without being opened at all.

**Your own previous runs.** Every run loads `journalscrape/journal-library.json` - everything
previous runs collected - and compares each entry against the container's own unlock tier.
Unchanged entries are copied forward unopened, so a run with nothing new is fast and sends almost
nothing to the server.

**What the collector already holds.** If an upload endpoint is configured (below), the run begins by
asking it for a key -> tier index and parking until it answers or six seconds pass. An entry
someone else has already read to a tier you cannot exceed is skipped: opening it would show you the
same pages, or fewer. This is what makes a *first* run on a fresh install fast, where the local
library is empty and every one of a few hundred entries would otherwise be opened.

Tiers rank as the next-threshold number, with `complete` above all of them:

| collector | you | opened? | why |
|---|---|---|---|
| complete | complete | no | nothing to add |
| complete | 1,000 | no | you would see less than is already recorded |
| 1,000 | complete | **yes** | you are ahead |
| 200 | 10 | no | the collector is further along |
| 10 | 200 | **yes** | you are further along |
| never seen | anything | **yes** | new to everyone |
| anything | unreadable | **yes** | being slow is the safe way to be wrong |

The comparison is deliberately asymmetric, because a tier is a kill threshold rather than a count
of pages and each mob has its own ladder. Being **behind** the collector is decisive - the same or
a lower threshold on the same mob can never show more - but being ahead is only a maybe: across the
collected data, tier `100` appears with one page unlocked and with two, and tier `1000` with two, so
two different thresholds can mean the same pages. Whether your extra threshold actually revealed
anything cannot be read off the index, so the entry is opened to find out. That costs one dialog,
and only for the entries where you are genuinely ahead.

A skipped entry is **not** written into that run's output - a copy this client never read is not
this client's to send - though a local copy from an earlier run still goes out with the file as
usual. An unreachable or unconfigured collector fails open: one line in chat, and a full scrape.

The tier itself is taken from the progress line's DENOMINATOR (`[ 148 / 1,000 ]` -> `1,000`, or
`complete`), not the progress itself. The numerator moves with every kill without unlocking
anything; the denominator is the next threshold and only changes when a tier is actually crossed -
which is exactly when the dialog gains content.

A full cold run, for scale - every entry opened, one disconnect resumed through:

```
[journal] library: 0 entries from previous runs
[journal] 279 refreshed, 0 unchanged, 0 already collected, 6 tabs in 291.8s (survived 1 disconnect)
```

The notices the shortcuts print, when they apply:

```
[journal] library: 279 entries from previous runs
[journal] the collector knows 279 entries - those will be walked past, not opened
```

Entries are identified by **tab + name + enemy level**, never by slot or page. A newly unlocked
entry appears in the middle of a tab rather than at the end - the monster list is ordered by level -
so everything after it shifts onto different slots and pages, and a positional identity would
mis-match every entry below the insertion point. Entries a run never reaches are kept rather than
dropped: the library is everything ever scraped, not a snapshot of one walk.

## Pooling with other players

Nobody has everything unlocked, so a scrape is worth more merged with someone else's. When a
finished run reports its file, an `[upload]` button appears beside it; clicking it POSTs the library
to whatever collector you have configured. Nothing is ever sent without that click.

`config/journalscrape.txt` is written on first launch:

```
upload-url=https://your-collector.example/api/upload
upload-token=
known-url=
```

Leaving `upload-url` empty disables uploading and the skip-what-is-known check together.
`known-url` is derived from `upload-url` (`.../api/upload` -> `.../api/known`) and only needs
setting if the index lives elsewhere; `known-url=off` keeps uploading but stops the check.
`upload-token`, if set, is sent as `X-Upload-Token`.

### What leaves your machine

Everything the mod scrapes is written locally under `journalscrape/` and stays there unless you
send it. Out of the box there is no collector configured, so nothing leaves at all.

**When you click `[upload]`**, the mod POSTs the scrape file:

* every journal entry it holds - tab, name, enemy level, unlock tier, page text, drops and their
  item data
* the run's own numbers - when it ran, how long it took, how many entries were refreshed, reused or
  skipped, how many commands it sent, how many times it was disconnected
* your **in-game name**, in an `X-Player` header, so a contribution can be attributed. The
  reference collector stores it in the raw upload's filename.
* the `upload-token`, if you set one

It does not read or send anything else: no account details, no file paths, no other mods, no chat,
nothing about the rest of your game. Nothing is uploaded without that click.

**One request is not click-gated.** When a collector is configured, each scrape begins by asking it
what it already holds (see above). That request carries no name and no scrape data - only the token
if set - but like any web request it reaches the collector from your IP address, and its timing
tells the operator that you started a scrape. `known-url=off` stops it.

**Where it goes is your choice.** The endpoints are whatever you put in the config; this mod ships
with none and is not tied to any particular collector. Anything you upload is then in the hands of
whoever runs it, so it is worth knowing that the reference collector serves the merged result as a
public web page - your in-game name is not published on it, but the entries you contributed are.

The two endpoints a collector has to provide:

* `POST /api/upload` - body is the library JSON, `X-Player` names the sender. It should MERGE
  rather than replace: entries come from players with different amounts unlocked, so an entry
  belongs to whoever has read the most of it. A reply containing `received`, `newEntries` and
  `datasetAfter` is summarised in chat.
* `GET /api/known` - `{"tiers": {"<key>": "<tier>"}}` for everything it holds. Only the tier is
  needed, which keeps this a few KB rather than the whole dataset.

## What it captures

Per entry: tab, page, name, container slot, enemy level, and every dialog page - title, body text,
and any **drops**.

Each drop carries the item's tooltip **as the game rendered it**, split into coloured runs, so it
can be shown the way a player already knows how to read it rather than as a table in a new format:

```json
{"t": "+(6 to 10) Fire Damage",
 "r": [{"t": "+(6 to 10) Fire Damage", "c": "#356EE4"}]}
```

Colours are resolved per segment by walking the component tree. Reading the line's own style
instead is wrong and quietly so: the game wraps every lore line in a dark purple placeholder style,
so a whole tooltip comes back purple.

Drop names also carry `ShowItem` hovers containing the server's own loot definition, kept verbatim
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

Things that cost a debugging round each, and will again in any rewrite:

* a dialog button's `active` flag is **always true** - the end of the pages is the button's
  `action()` being empty
* `Style` accessors are `getHoverEvent()` / `getClickEvent()`, not the record-style names
* a chat button's `run_command` dispatches through `ClientPacketListener.sendUnattendedCommand`,
  **not** `sendCommand` - hooking only the latter makes the button silently do nothing
* closing a dialog does not reliably return you to the grid, so the command is re-issued - and
  because that send passes through this mod's own hook, a running scrape must refuse to re-arm
* grid pages OVERLAP BY ONE ENTRY, so the stride is 31 rather than 32, and entries must be keyed by
  name AND level - duplicate names are legitimate, the same mob appearing at two enemy levels
* the pagination row is re-sent constantly and individual slots read EMPTY mid-refresh, so the
  buttons are found by name across the row rather than at a fixed slot, and every decision is made
  on a page fingerprint that has held still for a tick
* the journal may reopen on a different page than the one being walked, so every reopen checks that
  fingerprint and navigates back when it does not match

`mc.screen` does not exist in 26.2: the current screen is `mc.gui.screen()`.

## Building

```sh
export JAVA_HOME=/path/to/jdk-25
./gradlew build          # -> build/libs/journalscrape-1.0.0.jar
```

No local game install needed - the compile classpath is fetched from Mojang's piston metadata and
sha1-verified, then cached under `build/minecraft/26.2/`.

## Status

Working. A full run has completed end to end against a live journal - 279 entries across 6 tabs in
292 s, including one disconnect it resumed through - with pagination, page-overlap de-duplication,
tab walking, tier-diffed incremental runs and the tooltip capture all exercised.

**Not yet exercised in game:** the collector index check (`GET /api/known`) that skips entries
other players have already read. Its ranking rules are unit-tested against a real index and the
fetch fails open, but no live run has used it yet.

## License

MIT.
