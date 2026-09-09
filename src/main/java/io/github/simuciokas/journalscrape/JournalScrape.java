package io.github.simuciokas.journalscrape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.simuciokas.journalscrape.mixin.DialogScreenAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * Scrapes the server's {@code /journal} knowledge GUI into JSON, then tells you where it went.
 *
 * <p>WHY A STATE MACHINE. Every page turn is a server round trip, so the walk has to wait - and a
 * mod cannot sleep: blocking the client thread freezes the very thread the reply arrives on. The
 * walk is therefore a state advanced once per client tick.
 *
 * <p>IT WAITS ON CONDITIONS, NOT ON A CLOCK. Each waiting state watches for the thing it is
 * actually waiting for - the grid appearing, a dialog opening, the dialog instance being replaced,
 * the page's first entry changing - and moves on the tick it happens. A flat delay after every
 * action cost ~6s per entry however fast the server answered.
 *
 * <p>IT WALKS EVERY TAB, AND EVERY PAGE OF EACH. The tab list is read off the top row rather than
 * hardcoded, skipping empty slots - and never slot 8, which is the Region Filter and would change
 * what the journal shows rather than move between categories.
 *
 * <p>IT WALKS EVERY PAGE OF THE TAB. Pages OVERLAP BY ONE ENTRY - page N's last slot is page N+1's
 * first - so the stride is 31, not 32, and entries are de-duplicated by name AND level: duplicate
 * names are legitimate ("Stone Slime" exists at Enemy Level 14 and 15) so name alone would silently
 * drop one. The end of the tab is the page refusing to change when Next is clicked.
 *
 * <p>REOPENING IS THE AWKWARD PART. Reading an entry closes the grid, and the journal may reopen on
 * a different page than the one being walked. Every reopen therefore checks the page fingerprint
 * and, when it does not match, navigates back: tab 0, page back to the start, then forward to the
 * page it was on.
 *
 * <p>TRAPS, each of which cost a debugging round:
 * <ul>
 *   <li>a dialog button's {@code active} flag is ALWAYS true; the end of the pages is the button's
 *       action being empty
 *   <li>{@code Style} accessors are {@code getHoverEvent()}/{@code getClickEvent()}
 *   <li>{@code DialogScreen.dialog} erases to {@code Dialog}, not {@code Object} - an accessor
 *       typed {@code Object} matches nothing and, being {@code required}, crashes the game at launch
 *   <li>the reopen sends {@code /journal} through this mod's own hook, so a running scrape must
 *       refuse to re-arm
 *   <li>{@code mc.screen} does not exist in 26.2; it is {@code mc.gui.screen()}
 * </ul>
 */
public final class JournalScrape {

    public static final String MOD_ID = "journalscrape";

    private static final String COMMAND = "journal";
    private static final String UPLOAD_COMMAND = "journalupload";
    private static final int JOURNAL_SLOTS = 90;
    /**
     * Category tabs live in the top row. Slot 8 is EXCLUDED on purpose: it is the Region Filter,
     * not a tab, and clicking it changes what the journal shows rather than moving between
     * categories. Slots that hold no item (4 and 7 in the layout seen) are skipped automatically,
     * so the tab list is discovered rather than hardcoded.
     */
    private static final int FIRST_TAB_SLOT = 0;
    private static final int LAST_TAB_SLOT = 7;
    /**
     * The pagination row, searched by BUTTON NAME rather than by slot.
     *
     * <p>The buttons are duplicated for a wider click target - Previous at 46 and 47, Next at 51
     * and 52 - but the server re-sends that row constantly and individual slots are transiently
     * EMPTY while it does. Clicking a fixed slot therefore does nothing whenever it happens to be
     * mid-refresh, and the walk reads that silence as "there is no next page", ending a tab early
     * or deciding it is already on page one. Observed live: three reads of the row in a row gave
     * {47,51,52}, {46,47,51,52} and {46,47,51,52}.
     */
    private static final int NAV_FIRST = 45;
    private static final int NAV_LAST = 53;

    // THE WALK PACES ITSELF IN BURSTS. It sends /journal once per entry; flat out that reads as
    // command spam, and servers kick for it - which floods chat with join/leave messages and, on a
    // journal of any size, ends up slower than pacing would have been. A gap before EVERY entry
    // taxes the whole run to satisfy a limit that only bites in bursts, so instead the walk runs at
    // full speed for a batch and then takes a breath: PAUSE_SECONDS after every PAUSE_EVERY
    // entries. The pause grows after each disconnect, up to a ceiling, and the value that worked is
    // remembered for the next run - a kick is a lesson learned once rather than every time.
    private static final int TIMEOUT = 60;             // waiting for a server-pushed screen
    private static final int SHORT_TIMEOUT = 20;       // "did anything change?" probe
    private static final int KNOWN_TIMEOUT = 120;      // 6s for the collector to answer
    private static final int MAX_PAGE_STEPS = 40;      // a book this long means something is wrong
    private static final int MAX_PAGES = 8;            // dialog pages within one entry
    private static final int PAUSE_EVERY = 10;         // entries per burst
    private static final int PAUSE_SECONDS = 3;        // breath between bursts
    private static final int PAUSE_STEP = 1;           // added to the breath after a disconnect
    private static final int PAUSE_MAX = 15;           // a breath this long means give up instead
    private static final int ESCAPE_KEY = 256;

    private static final int[] ENTRY_SLOTS = buildEntrySlots();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private enum State {
        IDLE, WAIT_KNOWN, WAIT_GRID, CLICK_TAB, WAIT_TAB, PAGE_BACK, WAIT_PAGE_BACK,
        NAV_FORWARD, WAIT_NAV_FORWARD, OPEN_ENTRY, WAIT_DIALOG, READ_PAGE, WAIT_NEXT_PAGE,
        NEXT_GRID_PAGE, WAIT_GRID_PAGE, REOPEN, WAIT_REGRID, WAIT_RECONNECT
    }

    private static State state = State.IDLE;
    private static int wait;
    private static int slotIndex;              // position within ENTRY_SLOTS on this page
    private static int dialogPage;
    private static int steps;                  // guard against paging forever
    private static int limit;                  // max entries to capture
    private static int gridPage;               // 0-based page of the tab being walked
    private static int[] tabs = new int[0];    // container slots of the category tabs
    private static String[] tabNames = new String[0];
    private static int tabIndex;
    /** Ticks to let a fresh join settle before resuming - a command sent too early is wasted. */
    private static final int REJOIN_SETTLE = 60;

    private static int commandsSent;
    private static int pauseEvery;             // entries per burst, 0 = never pause
    private static int pauseTicks;             // length of the breath, learned across runs
    private static int pauseStep;
    private static int pauseMax;
    private static int sinceBurst;             // entries opened since the last breath
    private static int paceLeft;               // ticks still to wait before the next reopen
    private static int stopKey;                // an ADDITIONAL key to end a run; Escape is built in
    private static long deadline;              // 0 = no time limit
    private static boolean stopping;
    /** Set from the key handler, acted on by the next tick: stopping touches files and chat. */
    private static volatile boolean stopRequested;
    /** Cover the screen while the walk runs? See ScrapeOverlay for why this defaults to on. */
    private static volatile boolean overlay = true;
    private static volatile String stopReason = "";
    private static int navAt;                  // page reached while navigating back to gridPage
    private static String pageMarker = "";     // fingerprint of the page being walked
    private static String lastFp = "";         // previous tick's fingerprint, to spot settling
    private static boolean fpStable;           // fingerprint unchanged since last tick?
    private static Dialog lastDialog;
    private static String currentKey = "";     // marked as seen only once the entry is finished
    private static Path outFile;               // fixed at the start so partial saves land in it
    private static Path lastWritten;           // what the [upload] button in chat refers to
    /** Everything ever scraped, keyed by tab|name|level, carried between runs. */
    private static Map<String, JsonObject> library = new LinkedHashMap<>();
    /**
     * What the COLLECTOR already knows: key -> unlock tier, fetched once per run.
     *
     * <p>The local library only knows what this client has read. Someone else may already have
     * read an entry to a tier this player cannot exceed, and opening it would produce nothing the
     * collector does not hold - so it is walked past. Empty when the collector is unreachable or
     * not configured, which simply means nothing is skipped on its account.
     */
    private static volatile JsonObject remoteTiers;
    private static volatile boolean remoteDone;
    private static boolean force;              // rescrape even where the tier is unchanged
    private static int reused;
    private static int refreshed;
    private static int skipped;                // already known to the collector, never opened
    /**
     * Entries the walk has passed this run, however it passed them - read, copied forward, or
     * skipped as already collected.
     *
     * <p>SEPARATE FROM `seen` ON PURPOSE. `seen` is the page-overlap de-duplicator and is CLEARED
     * at every tab boundary, because the overlap it guards against is between pages of one
     * category; using it for progress made the bar restart at every tab. This one only ever goes
     * up, which is the only thing a progress bar may do.
     */
    private static int walked;
    /** Entries the library says exist, per tab in walk order, for the bar's total and its ticks. */
    private static int[] tabExpected = new int[0];
    private static int expectedTotal;
    private static int kicks;
    private static JsonObject currentEntry;
    private static JsonArray entries;
    private static Set<String> seen;
    private static long startedAt;

    private JournalScrape() {
    }

    private static int[] buildEntrySlots() {
        final List<Integer> out = new ArrayList<>();
        for (int s = 9; s <= 44; s++) {
            if (s != 13 && s != 22 && s != 31 && s != 40) {
                out.add(s);
            }
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * The chat button's command. Unlike the journal command this one IS cancelled - it exists only
     * for the client, and letting it reach the server would just earn an "unknown command" reply.
     * A button is used rather than a typed command because it appears exactly where the file path
     * is already shown, and because uploading should be a deliberate click.
     */
    public static boolean onUploadCommand(String command) {
        if (command == null) {
            return false;
        }
        final String c = command.trim().toLowerCase();
        if (!c.equals(UPLOAD_COMMAND) && !c.startsWith(UPLOAD_COMMAND + " ")) {
            return false;
        }
        if (lastWritten == null) {
            say(Component.literal("nothing scraped yet this session").withStyle(ChatFormatting.YELLOW));
        } else {
            Uploader.upload(lastWritten);
        }
        return true;
    }

    /** True when the command was ours. Never cancels: the journal still has to open. */
    public static boolean onCommandSent(String command) {
        if (command == null) {
            return false;
        }
        final String c = command.trim().toLowerCase();
        if (!c.equals(COMMAND) && !c.startsWith(COMMAND + " ")) {
            return false;
        }
        // The reopen re-sends this command and it comes back through here; without this guard the
        // walk re-arms on every entry and discards what it has already collected.
        if (state != State.IDLE) {
            return false;
        }
        limit = Integer.MAX_VALUE;             // bare /journal walks every tab
        force = false;
        final String[] parts = c.split("\\s+");
        if (parts.length > 1) {
            if (parts[1].equals("force") || parts[1].equals("all")) {
                force = true;
            } else {
                try {
                    limit = Math.max(1, Integer.parseInt(parts[1]));
                } catch (NumberFormatException ignored) {
                    return false;
                }
            }
        }
        slotIndex = 0;
        dialogPage = 0;
        steps = 0;
        tabs = new int[0];
        tabNames = new String[0];
        tabIndex = 0;
        commandsSent = 1;
        gridPage = 0;
        navAt = 0;
        pageMarker = "";
        entries = new JsonArray();
        seen = new HashSet<>();
        kicks = 0;
        reused = 0;
        refreshed = 0;
        skipped = 0;
        walked = 0;
        tabExpected = new int[0];
        expectedTotal = 0;
        currentKey = "";
        remoteTiers = null;
        remoteDone = false;
        pauseEvery = Math.max(0, Uploader.setting("pause-every", PAUSE_EVERY));
        pauseStep = Math.max(0, Uploader.setting("pause-step-seconds", PAUSE_STEP)) * 20;
        pauseMax = Math.max(1, Uploader.setting("max-pause-seconds", PAUSE_MAX)) * 20;
        pauseTicks = Math.max(1, Uploader.setting("pause-seconds", PAUSE_SECONDS)) * 20;
        pauseTicks = Math.min(Math.max(pauseTicks, loadPace(pauseTicks)), pauseMax);
        sinceBurst = 0;
        paceLeft = 0;
        stopRequested = false;
        stopReason = "";
        stopKey = keyCode(Uploader.setting("stop-key", "none"));
        overlay = !Uploader.setting("overlay", "true").equalsIgnoreCase("false");
        final int budget = Math.max(0, Uploader.setting("max-minutes", 0));
        deadline = budget == 0 ? 0L : System.currentTimeMillis() + budget * 60_000L;
        stopping = false;
        loadLibrary();
        expectedTotal = library.size();
        startedAt = System.currentTimeMillis();
        // Chosen up front so every partial save through the run lands in the same file.
        outFile = Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_ID)
                .resolve("journal-" + LocalDateTime.now().format(STAMP) + ".json");
        // ASK THE COLLECTOR FIRST. On a background thread, with the walk parked until it answers
        // or the wait runs out - a slow or dead collector must not stop the scrape, only make it do
        // more work.
        if (!force && !Uploader.knownUrl().isBlank()) {
            state = State.WAIT_KNOWN;
            wait = KNOWN_TIMEOUT;
            Thread.ofVirtual().name("journalscrape-known").start(() -> {
                final JsonObject t = Uploader.fetchKnown();
                remoteTiers = t;
                remoteDone = true;
            });
        } else {
            state = State.WAIT_GRID;
            wait = TIMEOUT;
        }
        say(Component.literal((limit == Integer.MAX_VALUE
                        ? "scraping every tab"
                        : ("scraping up to " + limit + " entries"))
                        + " - leave the GUI alone; a kick will pause it, not end it")
                .withStyle(ChatFormatting.GRAY));
        say(Component.literal((pauseEvery == 0
                        ? "no pacing - a kick is likely"
                        : String.format("pausing %.0fs every %d entries", pauseTicks / 20.0, pauseEvery))
                        + (deadline == 0 ? "" : (", stopping after " + budget + " min"))
                        + " - press ESC to stop and keep what is done")
                .withStyle(ChatFormatting.DARK_GRAY));
        return true;
    }

    public static void tick(Minecraft mc) {
        if (state == State.IDLE || mc == null || mc.gui == null) {
            return;
        }
        // The container's contents flicker while the server re-sends them, so every decision
        // below is made on a fingerprint that has held still for a tick. Acting on a half-drawn
        // page is what made the walk re-navigate at random.
        final String fp = pageFingerprint(mc);
        fpStable = fp.equals(lastFp);
        lastFp = fp;

        // STOPPING IS A FIRST-CLASS OUTCOME, not a failure: a journal with hundreds of entries is
        // not something to be trapped in. Either signal writes the file and reports it, and the next
        // run carries on from what is collected rather than starting over.
        if (!stopping && state != State.IDLE) {
            if (stopRequested) {
                stopping = true;
                stopRun(mc, stopReason);
                return;
            }
            if (stopKey != 0 && isKeyHeld(mc, stopKey)) {
                stopping = true;
                stopRun(mc, "stopped on the "
                        + Uploader.setting("stop-key", "none").toUpperCase() + " key");
                return;
            }
            if (deadline != 0 && System.currentTimeMillis() > deadline) {
                stopping = true;
                stopRun(mc, "time is up");
                return;
            }
        }

        // A KICK IS NOT THE END OF THE WALK. Losing the connection used to abandon the run and
        // throw away everything collected; now the progress is written out and the walk parks
        // until you are back in, then picks up at the entry it was on.
        final boolean connected = mc.getConnection() != null && mc.player != null;
        if (!connected) {
            if (state != State.WAIT_RECONNECT) {
                kicks++;
                // The server said no. Breathe for longer before the next burst, and remember it
                // so the next run does not have to relearn the same lesson.
                if (pauseStep > 0 && pauseTicks < pauseMax) {
                    pauseTicks = Math.min(pauseMax, pauseTicks + pauseStep);
                    savePace(pauseTicks);
                    say(Component.literal(String.format("pausing %.0fs every %d entries from now on",
                                    pauseTicks / 20.0, pauseEvery))
                            .withStyle(ChatFormatting.YELLOW));
                }
                abandonEntryInProgress();
                save(false);
                say(Component.literal("disconnected - " + entries.size()
                                + " entries saved so far; will resume when you are back in")
                        .withStyle(ChatFormatting.YELLOW));
                state = State.WAIT_RECONNECT;
                wait = REJOIN_SETTLE;
            }
            return;
        }
        switch (state) {
            case WAIT_KNOWN -> {
                if (remoteDone || --wait <= 0) {
                    final int n = (remoteTiers == null) ? 0 : remoteTiers.size();
                    if (n > 0) {
                        say(Component.literal("the collector knows " + n
                                        + " entries - those will be walked past, not opened")
                                .withStyle(ChatFormatting.DARK_GRAY));
                    } else if (remoteDone) {
                        say(Component.literal("the collector had nothing to share - scraping everything")
                                .withStyle(ChatFormatting.DARK_GRAY));
                    } else {
                        say(Component.literal("the collector did not answer in time - scraping everything")
                                .withStyle(ChatFormatting.DARK_GRAY));
                    }
                    state = State.WAIT_GRID;
                    wait = TIMEOUT;
                }
            }
            case WAIT_GRID -> {
                if (gridOpen(mc) && fpStable) {
                    if (tabs.length == 0) {
                        discoverTabs(mc);
                        if (tabs.length == 0) {
                            fail("found no category tabs");
                            return;
                        }
                        say(Component.literal("found " + tabs.length + " tabs: "
                                        + String.join(", ", tabNames)).withStyle(ChatFormatting.DARK_GRAY));
                        countExpectedPerTab();
                    }
                    state = State.CLICK_TAB;
                } else if (--wait <= 0) {
                    fail("the journal did not open");
                }
            }
            // Normalise: first tab, then page back to the start. Both detect "already there" by
            // nothing changing before the short timeout.
            case CLICK_TAB -> {
                pageMarker = fp;
                clickSlot(mc, tabs[tabIndex]);
                state = State.WAIT_TAB;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_TAB -> {
                if (changed(fp)) {
                    state = State.PAGE_BACK;
                } else if (--wait <= 0) {
                    state = State.PAGE_BACK;
                }
            }
            case PAGE_BACK -> {
                if (++steps > MAX_PAGE_STEPS) {
                    fail("could not reach the first page");
                    return;
                }
                pageMarker = fp;
                clickNav(mc, "Previous");
                state = State.WAIT_PAGE_BACK;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_PAGE_BACK -> {
                if (changed(fp)) {
                    state = State.PAGE_BACK;          // moved, so there may be further back to go
                } else if (--wait <= 0) {
                    navAt = 0;                        // this is page 0
                    pageMarker = fp;
                    state = (navAt < gridPage) ? State.NAV_FORWARD : State.OPEN_ENTRY;
                }
            }
            // Walk forward to the page we were on before the grid closed.
            case NAV_FORWARD -> {
                if (++steps > MAX_PAGE_STEPS) {
                    fail("could not return to page " + (gridPage + 1));
                    return;
                }
                pageMarker = fp;
                clickNav(mc, "Next");
                state = State.WAIT_NAV_FORWARD;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_NAV_FORWARD -> {
                if (changed(fp)) {
                    navAt++;
                    pageMarker = fp;
                    state = (navAt < gridPage) ? State.NAV_FORWARD : State.OPEN_ENTRY;
                } else if (--wait <= 0) {
                    fail("ran out of pages returning to page " + (gridPage + 1));
                }
            }
            case OPEN_ENTRY -> openEntry(mc);
            case WAIT_DIALOG -> {
                final Dialog d = dialogOf(mc);
                if (d != null) {
                    lastDialog = d;
                    state = State.READ_PAGE;
                } else if (--wait <= 0) {
                    endEntry(mc, "no dialog opened");
                }
            }
            case READ_PAGE -> readPage(mc);
            case WAIT_NEXT_PAGE -> {
                final Dialog d = dialogOf(mc);
                if (d != null && d != lastDialog) {   // the server replaced it: the page turned
                    lastDialog = d;
                    state = State.READ_PAGE;
                } else if (--wait <= 0) {
                    endEntry(mc, "next page never arrived");
                }
            }
            // The page is done: advance the GRID. A page that refuses to change is the last one.
            case NEXT_GRID_PAGE -> {
                if (++steps > MAX_PAGE_STEPS) {
                    nextTab(mc);
                    return;
                }
                pageMarker = fp;
                clickNav(mc, "Next");
                state = State.WAIT_GRID_PAGE;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_GRID_PAGE -> {
                if (changed(fp)) {
                    gridPage++;
                    navAt = gridPage;
                    slotIndex = 0;
                    pageMarker = fp;
                    state = State.OPEN_ENTRY;
                } else if (--wait <= 0) {
                    nextTab(mc);                      // no further pages: on to the next category
                }
            }
            case WAIT_RECONNECT -> {
                // Back in the world. Let the join settle, then rejoin the walk through the normal
                // reopen path so the command still respects the anti-spam gap.
                if (--wait <= 0) {
                    say(Component.literal("resuming at entry " + (entries.size() + 1))
                            .withStyle(ChatFormatting.GRAY));
                    // The page fingerprint is KEPT on purpose. Reopening the journal returns you to
                    // the page you were on, so the reopen check below usually matches and the walk
                    // carries straight on; clearing it here forced a needless re-navigation to tab
                    // 0 and page 1 after every kick. The mismatch path is still there if the server
                    // does put us somewhere else.
                    steps = 0;
                    reopen();
                }
            }
            case REOPEN -> {
                // The grid check comes FIRST: the gap exists to space out COMMANDS, so when the
                // journal is already open there is nothing to space out and nothing to wait for.
                if (gridOpen(mc)) {
                    state = State.OPEN_ENTRY;
                } else if (paceLeft > 0) {
                    paceLeft--;                       // the gap that keeps the server calm
                } else if (mc.getConnection() == null) {
                    state = State.WAIT_RECONNECT;   // unreachable in practice: tick() catches a
                    wait = REJOIN_SETTLE;           // dropped connection first. Defensive only.
                } else {
                    mc.setScreenAndShow(null);
                    mc.getConnection().sendCommand(COMMAND);
                    commandsSent++;
                    state = State.WAIT_REGRID;
                    wait = TIMEOUT;
                }
            }
            case WAIT_REGRID -> {
                if (gridOpen(mc) && fpStable) {
                    // Reopened on the right page? Otherwise navigate back to it.
                    if (fp.equals(pageMarker)) {
                        state = State.OPEN_ENTRY;
                    } else {
                        steps = 0;
                        state = State.CLICK_TAB;
                    }
                } else if (--wait <= 0) {
                    fail("the journal did not reopen");
                }
            }
            default -> { }
        }
    }

    /**
     * Reads the category tabs off the top row. Anything empty is skipped, and the Region Filter in
     * slot 8 is never included - clicking it would change the journal's contents mid-walk.
     */
    private static void discoverTabs(Minecraft mc) {
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null) {
            return;
        }
        final List<Integer> slots = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        for (int slot = FIRST_TAB_SLOT; slot <= LAST_TAB_SLOT; slot++) {
            final ItemStack stack = g.getMenu().slots.get(slot).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            slots.add(slot);
            names.add(plain(stack.getHoverName()));
        }
        tabs = slots.stream().mapToInt(Integer::intValue).toArray();
        tabNames = names.toArray(new String[0]);
    }

    /** Moves to the next category, or finishes when the last one has been walked. */
    private static void nextTab(Minecraft mc) {
        tabIndex++;
        if (tabIndex >= tabs.length) {
            finish(mc);
            return;
        }
        gridPage = 0;
        navAt = 0;
        slotIndex = 0;
        steps = 0;
        // De-duplication is PER TAB: the overlap it exists for is between pages of one category,
        // and the same name could legitimately appear in two categories.
        seen.clear();
        say(Component.literal("tab " + (tabIndex + 1) + "/" + tabs.length + ": " + tabNames[tabIndex]
                        + " (" + entries.size() + " entries so far)").withStyle(ChatFormatting.DARK_GRAY));
        save(false);
        if (gridOpen(mc)) { state = State.CLICK_TAB; } else { reopen(); }
    }

    private static String currentTabName() {
        return (tabIndex < tabNames.length) ? tabNames[tabIndex] : "";
    }

    private static AbstractContainerScreen<?> grid(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> s
                && s.getMenu().slots.size() == JOURNAL_SLOTS ? s : null;
    }

    private static boolean gridOpen(Minecraft mc) {
        return grid(mc) != null;
    }

    /** Has the page moved since pageMarker was taken - and settled again? */
    private static boolean changed(String fp) {
        return fpStable && !fp.isEmpty() && !fp.equals(pageMarker);
    }

    /**
     * Every entry name on the page, joined. A single slot is too weak a fingerprint: it is blank
     * for a moment on every refresh, and a blank compares unequal to whatever it was, which makes
     * the walk think the page changed when it has not.
     */
    private static String pageFingerprint(Minecraft mc) {
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder(256);
        for (int slot : ENTRY_SLOTS) {
            final ItemStack stack = g.getMenu().slots.get(slot).getItem();
            sb.append(stack.isEmpty() ? "-" : plain(stack.getHoverName())).append('|');
        }
        return sb.toString();
    }

    /** Clicks the pagination button with this label, wherever the server has put it this tick. */
    private static boolean clickNav(Minecraft mc, String label) {
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null) {
            return false;
        }
        for (int slot = NAV_FIRST; slot <= NAV_LAST; slot++) {
            final ItemStack stack = g.getMenu().slots.get(slot).getItem();
            if (!stack.isEmpty() && plain(stack.getHoverName()).contains(label)) {
                clickSlot(mc, slot);
                return true;
            }
        }
        return false;                          // mid-refresh: the retry comes round next tick
    }

    private static Dialog dialogOf(Minecraft mc) {
        return mc.gui.screen() instanceof DialogScreen<?> ds
                ? ((DialogScreenAccessor) ds).journalscrape$getDialog() : null;
    }

    private static String firstEntryName(Minecraft mc) {
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null) {
            return "";
        }
        final ItemStack stack = g.getMenu().slots.get(ENTRY_SLOTS[0]).getItem();
        return stack.isEmpty() ? "" : plain(stack.getHoverName());
    }

    private static void clickSlot(Minecraft mc, int slot) {
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null || mc.gameMode == null || mc.player == null) {
            return;
        }
        mc.gameMode.handleContainerInput(g.getMenu().containerId, slot, 0,
                ContainerInput.PICKUP, mc.player);
    }

    /**
     * The entry's identity: tab, name and enemy level. Never its slot or page - a newly unlocked
     * entry appears in the MIDDLE of a tab, not at the end, and everything after it shifts along.
     * Level is part of the key because one name can legitimately exist at two levels; it is empty
     * on the tabs that have no level line, where the name alone is unique.
     */
    private static String keyOf(ItemStack stack) {
        return currentTabName() + "|" + plain(stack.getHoverName()) + "|" + levelOf(stack);
    }

    private static String levelOf(ItemStack stack) {
        for (String t : loreOf(stack)) {
            if (t.startsWith("Enemy Level:")) {
                return t.substring("Enemy Level:".length()).trim();
            }
        }
        return "";
    }

    private static List<String> loreOf(ItemStack stack) {
        final List<String> out = new ArrayList<>();
        final ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                out.add(plain(line));
            }
        }
        return out;
    }

    /**
     * The unlock TIER, as shown in the container: "[ 148 / 1,000 ]" or "[ Complete! ]".
     *
     * <p>Deliberately the DENOMINATOR rather than the progress. The numerator moves with every kill
     * without unlocking anything, so keying on it would re-read entries that cannot have changed;
     * the denominator is the next threshold and only moves when a tier is actually crossed, which
     * is exactly when the dialog gains content.
     */
    private static String tierOf(ItemStack stack) {
        for (String t : loreOf(stack)) {
            if (!t.startsWith("[") || !t.endsWith("]")) {
                continue;
            }
            final String inner = t.substring(1, t.length() - 1).trim();
            if (inner.toLowerCase().startsWith("complete")) {
                return "complete";
            }
            final int slash = inner.indexOf('/');
            if (slash >= 0) {
                return inner.substring(slash + 1).replace(",", "").trim();
            }
        }
        return "";                            // no progress line: treat as always worth reading
    }

    private static void openEntry(Minecraft mc) {
        if (entries.size() >= limit) {
            finish(mc);
            return;
        }
        if (slotIndex >= ENTRY_SLOTS.length) {
            state = State.NEXT_GRID_PAGE;         // page exhausted: try the next one
            return;
        }
        final AbstractContainerScreen<?> g = grid(mc);
        if (g == null) {
            reopen();
            return;
        }
        final int slot = ENTRY_SLOTS[slotIndex];
        final ItemStack stack = g.getMenu().slots.get(slot).getItem();
        if (stack.isEmpty()) {
            slotIndex++;
            return;
        }
        // Pages overlap by one entry, so the boundary entry arrives twice - and re-reading it would
        // also re-open a dialog we have already walked.
        // Marked as seen only when the entry COMPLETES: adding it here would make a kick
        // mid-entry skip that entry forever on resume.
        final String key = keyOf(stack);
        if (seen.contains(key)) {
            slotIndex++;
            return;
        }
        // NOTHING NEW TO READ? Copy the previous run's pages forward and move on without opening
        // the entry at all - no dialog, and no reopen command afterwards.
        final String tier = tierOf(stack);
        final JsonObject known = library.get(key);
        if (!force && known != null && !tier.isEmpty()
                && tier.equals(known.has("tier") ? known.get("tier").getAsString() : null)) {
            // The identity is the KEY, never the position: new entries appear in the middle of a
            // tab (the monster list is ordered by level), which shifts everything after them onto
            // different slots and pages. Refresh that positional metadata so it describes where the
            // entry is now rather than where it used to be.
            known.addProperty("page", gridPage + 1);
            known.addProperty("slot", slot);
            entries.add(known);
            seen.add(key);
            reused++;
            walked++;
            slotIndex++;
            return;
        }
        // ALREADY KNOWN TO EVERYONE ELSE? Then there is nothing to gain from opening it. Not added
        // to this run's entries: the point is that the collector holds it, and a copy this client
        // never read is not this client's to send. A local copy, if there is one, still goes out
        // with the file as usual.
        if (!force && covered(key, tier)) {
            seen.add(key);
            skipped++;
            walked++;
            slotIndex++;
            return;
        }
        currentKey = key;
        currentEntry = new JsonObject();
        currentEntry.addProperty("key", key);
        currentEntry.addProperty("tier", tier);
        currentEntry.addProperty("tab", currentTabName());
        currentEntry.addProperty("page", gridPage + 1);
        currentEntry.addProperty("slot", slot);
        currentEntry.addProperty("name", plain(stack.getHoverName()));
        // The LAST separator: the key is tab|name|level and monster names are not pipe-free by
        // luck alone - taking the first one recorded "Sewer Rat|21" as the enemy level.
        final String level = key.substring(key.lastIndexOf('|') + 1);
        if (!level.isEmpty()) {
            currentEntry.addProperty("enemyLevel", level);
        }
        currentEntry.add("pages", new JsonArray());
        dialogPage = 0;
        lastDialog = null;
        clickSlot(mc, slot);
        state = State.WAIT_DIALOG;
        wait = TIMEOUT;
    }

    private static void readPage(Minecraft mc) {
        if (!(mc.gui.screen() instanceof DialogScreen<?> ds)) {
            endEntry(mc, "dialog vanished");
            return;
        }
        final JsonObject page = DialogReader.read(lastDialog);
        currentEntry.getAsJsonArray("pages").add(page);
        dialogPage++;

        final boolean more = page.has("hasNext") && page.get("hasNext").getAsBoolean();
        if (more && dialogPage < MAX_PAGES) {
            if (DialogReader.pressNext(ds)) {
                state = State.WAIT_NEXT_PAGE;
                wait = TIMEOUT;
                return;
            }
            page.addProperty("pressFailed", true);
        }
        endEntry(mc, null);
    }

    /** Drop a half-read entry so the resume retries it rather than recording it truncated. */
    private static void abandonEntryInProgress() {
        currentEntry = null;
        currentKey = "";
        lastDialog = null;
    }

    private static void endEntry(Minecraft mc, String error) {
        if (currentEntry != null) {
            if (error != null) {
                currentEntry.addProperty("error", error);
            }
            entries.add(currentEntry);
            refreshed++;
            if (!currentKey.isEmpty()) {
                library.put(currentKey, currentEntry);
                seen.add(currentKey);
                walked++;
                currentKey = "";
            }
            currentEntry = null;
            if (entries.size() % 10 == 0) {
                say(Component.literal(entries.size() + " entries so far...").withStyle(ChatFormatting.DARK_GRAY));
                save(false);                  // a partial file beats losing the lot to a crash
            }
        }
        slotIndex++;
        mc.setScreenAndShow(null);
        reopen();
    }

    private static Path libraryFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_ID).resolve("journal-library.json");
    }

    /**
     * Loads what previous runs collected. Entries whose unlock tier has not moved are copied
     * forward instead of being re-read, which is the whole point: an entry that is skipped costs
     * no dialog round trips and no reopen command, so a run with nothing new is quick and quiet.
     */
    /**
     * Does the collector already hold this entry at a tier this player cannot beat?
     *
     * <p>Tiers rank as a number - the next threshold - with "complete" above all of them. Equal
     * ranks are covered too: the same tier shows the same pages. An unreadable tier on either side
     * ranks below everything, so the entry gets opened; being slow is the safe way to be wrong.
     */
    private static boolean covered(String key, String mine) {
        final JsonObject t = remoteTiers;
        if (t == null || !t.has(key)) {
            return false;
        }
        final int theirs = tierRank(t.get(key).getAsString());
        final int ours = tierRank(mine);
        return theirs >= 0 && ours >= 0 && theirs >= ours;
    }

    /** "complete" beats every threshold; "[ 148 / 1,000 ]" ranks as its 1000. -1 = unreadable. */
    private static int tierRank(String tier) {
        if (tier == null || tier.isEmpty()) {
            return -1;
        }
        if (tier.equalsIgnoreCase("complete")) {
            return Integer.MAX_VALUE;
        }
        final String digits = tier.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Go back for the grid, taking a breath once a burst is done.
     *
     * <p>Counted here rather than per entry because this is the path that sends a command: an entry
     * copied forward from the library or skipped as already collected costs nothing and should not
     * bring the pause any closer.
     */
    private static void reopen() {
        paceLeft = 0;
        if (pauseEvery > 0 && ++sinceBurst >= pauseEvery) {
            sinceBurst = 0;
            paceLeft = pauseTicks;
        }
        state = State.REOPEN;
    }

    /**
     * How many entries the library holds for each discovered tab, in walk order.
     *
     * <p>Used for the progress bar's tick marks, so one continuous bar can still show where the
     * tab boundaries fall - the structure the old per-tab bar was conveying by resetting.
     */
    private static void countExpectedPerTab() {
        tabExpected = new int[tabs.length];
        for (JsonObject e : library.values()) {
            if (!e.has("tab")) {
                continue;
            }
            final String tab = e.get("tab").getAsString();
            for (int i = 0; i < tabNames.length; i++) {
                if (tabNames[i].equals(tab)) {
                    tabExpected[i]++;
                    break;
                }
            }
        }
    }

    /** Cumulative fractions of the total at each tab boundary, or an empty array when unknown. */
    public static float[] overlayTabMarks() {
        if (expectedTotal <= 0 || tabExpected.length == 0) {
            return new float[0];
        }
        final float[] out = new float[tabExpected.length];
        int running = 0;
        for (int i = 0; i < tabExpected.length; i++) {
            running += tabExpected[i];
            out[i] = Math.min(1f, running / (float) Math.max(expectedTotal, walked));
        }
        return out;
    }

    /** True while a walk is in progress AND the cover is wanted - the overlay's only gate. */
    public static boolean isRunning() {
        return overlay && state != State.IDLE;
    }

    /**
     * Progress as a fraction, 0..1.
     *
     * <p>The numerator is `walked`, which never decreases. The denominator is what the library says
     * exists - and is GROWN when the walk passes more entries than that, since a run that unlocks
     * something new legitimately exceeds the old total and a bar must not sit pinned at full while
     * work continues.
     *
     * <p>With no library at all - a genuine first run - there is no entry count to work from, so
     * the bar falls back to TAB progress. That is coarse, but it is honest and it still reaches the
     * end, which the old estimate could not: it divided by a total it had no basis for.
     */
    public static float overlayProgress() {
        if (expectedTotal > 0) {
            return Math.min(1f, walked / (float) Math.max(expectedTotal, walked));
        }
        if (tabs.length > 0) {
            return Math.min(1f, tabIndex / (float) tabs.length);
        }
        return 0f;
    }

    /** The lines the cover shows. Kept short: this is read at a glance, mid-run. */
    public static List<String> overlayLines() {
        final List<String> out = new ArrayList<>(4);
        if (state == State.WAIT_KNOWN) {
            out.add("asking the collector what it already knows");
            return out;
        }
        if (state == State.WAIT_RECONNECT) {
            out.add("disconnected - waiting to rejoin");
            out.add(entries.size() + " entries saved so far; the walk resumes on its own");
            return out;
        }
        final int total = Math.max(expectedTotal, walked);
        final String of = expectedTotal > 0 ? (" of " + (walked > expectedTotal ? "" : "~") + total) : "";
        out.add("entry " + walked + of
                + (tabs.length > 0 ? ("   tab " + Math.min(tabIndex + 1, tabs.length)
                                      + "/" + tabs.length) : "")
                + "   page " + (gridPage + 1));
        out.add(refreshed + " read   " + reused + " unchanged   " + skipped + " already collected");
        final long secs = (System.currentTimeMillis() - startedAt) / 1000L;
        out.add(String.format("%d:%02d elapsed%s", secs / 60, secs % 60,
                              kicks == 0 ? "" : ("   " + kicks + " disconnect"
                                                 + (kicks == 1 ? "" : "s") + " survived")));
        return out;
    }

    /**
     * Escape ends a run. Called from the screen's key handler, so it only records the request - the
     * stop itself writes files and prints chat, which belongs on the tick that follows.
     */
    public static void onKeyPressed(int key) {
        if (key == ESCAPE_KEY && state != State.IDLE && !stopRequested) {
            stopRequested = true;
            stopReason = "stopped on Esc";
        }
    }

    /** Ends a run early but cleanly: what is collected is written, reported and uploadable. */
    private static void stopRun(Minecraft mc, String why) {
        mc.setScreenAndShow(null);
        abandonEntryInProgress();
        say(Component.literal(why + " - " + entries.size() + " entries this run")
                .withStyle(ChatFormatting.YELLOW));
        finish(mc);
    }

    private static boolean isKeyHeld(Minecraft mc, int key) {
        try {
            return InputConstants.isKeyDown(mc.getWindow(), key);
        } catch (Exception e) {
            return false;                          // no window, no keyboard, no stop key
        }
    }

    /** "k", "end", "left.shift" - whatever the config names, resolved to a GLFW code. */
    private static int keyCode(String name) {
        final String n = name.trim().toLowerCase();
        if (n.isEmpty() || n.equals("none") || n.equals("off")) {
            return 0;
        }
        try {
            return InputConstants.getKey(n.startsWith("key.") ? n : "key.keyboard." + n).getValue();
        } catch (Exception e) {
            say(Component.literal("stop-key '" + name + "' is not a key name; using K")
                    .withStyle(ChatFormatting.YELLOW));
            return InputConstants.getKey("key.keyboard.k").getValue();
        }
    }

    /** The pause that survived last time, so a kick is a lesson learned once. */
    private static int loadPace(int fallback) {
        try {
            final Path f = paceFile();
            if (Files.isRegularFile(f)) {
                return Math.max(fallback, Integer.parseInt(Files.readString(f).trim()));
            }
        } catch (Exception e) {
            // an unreadable note just means starting from the configured pace
        }
        return fallback;
    }

    private static void savePace(int ticks) {
        try {
            Files.createDirectories(paceFile().getParent());
            Files.writeString(paceFile(), Integer.toString(ticks));
        } catch (IOException e) {
            // losing the note costs one relearn, nothing more
        }
    }

    private static Path paceFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_ID).resolve("pace.txt");
    }

    private static void loadLibrary() {
        library = new LinkedHashMap<>();
        final Path f = libraryFile();
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            final JsonObject root = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
            final JsonArray old = root.getAsJsonArray("entries");
            if (old == null) {
                return;
            }
            for (JsonElement el : old) {
                final JsonObject e = el.getAsJsonObject();
                if (e.has("key")) {
                    library.put(e.get("key").getAsString(), e);
                }
            }
            say(Component.literal("library: " + library.size() + " entries from previous runs")
                    .withStyle(ChatFormatting.DARK_GRAY));
        } catch (Exception e) {
            say(Component.literal("could not read the library, starting fresh: " + e)
                    .withStyle(ChatFormatting.YELLOW));
            library = new LinkedHashMap<>();
        }
    }

    /** Writes what has been collected so far. Called on completion, on a kick, and periodically. */
    private static boolean save(boolean complete) {
        if (outFile == null) {
            return false;
        }
        // Entries this run has not reached are kept: the library is everything ever scraped, not a
        // snapshot of one walk.
        final JsonArray all = new JsonArray();
        final Set<String> written = new HashSet<>();
        for (JsonElement el : entries) {
            final JsonObject e = el.getAsJsonObject();
            if (e.has("key")) {
                written.add(e.get("key").getAsString());
            }
            all.add(e);
        }
        for (Map.Entry<String, JsonObject> kv : library.entrySet()) {
            if (!written.contains(kv.getKey())) {
                all.add(kv.getValue());
            }
        }
        final JsonObject root = new JsonObject();
        root.addProperty("scrapedAt", LocalDateTime.now().toString());
        root.addProperty("durationMs", System.currentTimeMillis() - startedAt);
        root.addProperty("entryCount", all.size());
        root.addProperty("thisRun", entries.size());
        root.addProperty("refreshed", refreshed);
        root.addProperty("reusedFromLibrary", reused);
        root.addProperty("skippedAlreadyCollected", skipped);
        root.addProperty("tabsWalked", Math.min(tabIndex + 1, Math.max(tabs.length, 1)));
        root.addProperty("commandsSent", commandsSent);
        root.addProperty("disconnects", kicks);
        root.addProperty("complete", complete);
        root.add("entries", all);
        try {
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, GSON.toJson(root));
            Files.writeString(libraryFile(), GSON.toJson(root));
            return true;
        } catch (IOException e) {
            say(Component.literal("could not write the scrape: " + e).withStyle(ChatFormatting.RED));
            return false;
        }
    }

    private static void finish(Minecraft mc) {
        mc.setScreenAndShow(null);
        final Path file = outFile;
        final Path dir = file.getParent();
        // A stopped run is not a finished one, and the file should not claim otherwise: `complete`
        // is what tells a reader whether the journal in here is all of it.
        if (!save(!stopping)) {
            state = State.IDLE;
            return;
        }

        lastWritten = file;
        final double secs = (System.currentTimeMillis() - startedAt) / 1000.0;
        final MutableComponent link = Component.literal(file.getFileName().toString())
                .withStyle(Style.EMPTY
                        .withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withClickEvent(new ClickEvent.OpenFile(dir))
                        .withHoverEvent(new HoverEvent.ShowText(
                                Component.literal(file.toAbsolutePath().toString())
                                        .append(Component.literal("\nclick to open the folder")
                                                .withStyle(ChatFormatting.GRAY)))));
        MutableComponent line = Component.literal(
                        String.format("%d refreshed, %d unchanged, %d already collected, "
                                        + "%d tab%s in %.1fs%s -> ",
                                refreshed, reused, skipped,
                                Math.min(tabIndex + 1, Math.max(tabs.length, 1)),
                                tabs.length == 1 ? "" : "s", secs,
                                kicks == 0 ? "" : (" (survived " + kicks + " disconnect"
                                        + (kicks == 1 ? "" : "s") + ")")))
                .withStyle(ChatFormatting.GREEN).append(link);
        if (Uploader.configured()) {
            line = line.append(Component.literal("  [upload]").withStyle(Style.EMPTY
                    .withColor(ChatFormatting.YELLOW)
                    .withBold(true)
                    .withClickEvent(new ClickEvent.RunCommand("/" + UPLOAD_COMMAND))
                    .withHoverEvent(new HoverEvent.ShowText(Component.literal(
                            "Send this scrape to the collector.\nEntries merge: a thinner journal "
                            + "never overwrites a fuller one.")))));
        }
        say(line);
        if (stopping) {
            say(Component.literal("run /journal again to carry on - what is collected is not re-read")
                    .withStyle(ChatFormatting.GRAY));
        }
        state = State.IDLE;
    }

    private static void fail(String why) {
        say(Component.literal("scrape stopped: " + why).withStyle(ChatFormatting.RED));
        state = State.IDLE;
    }

    static String plain(Component c) {
        return c == null ? "" : c.getString().replaceAll("[^\\x20-\\x7E]", "").trim();
    }

    static void say(Component body) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.gui == null) {
            return;
        }
        mc.gui.hud.getChat().addClientSystemMessage(
                Component.literal("[journal] ").withStyle(ChatFormatting.DARK_AQUA).append(body));
    }
}
