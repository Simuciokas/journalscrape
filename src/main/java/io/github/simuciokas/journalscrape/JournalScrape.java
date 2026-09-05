package io.github.simuciokas.journalscrape;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.simuciokas.journalscrape.mixin.DialogScreenAccessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
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
    private static final int JOURNAL_SLOTS = 90;
    private static final int TAB_SLOT = 0;             // first category tab
    private static final int PREV_SLOT = 46;           // "Previous" (46 and 47 are duplicates)
    private static final int NEXT_SLOT = 51;           // "Next" (51 and 52 are duplicates)

    /**
     * Minimum ticks between two {@code /journal} sends. The walk needs the command once per entry
     * to get the grid back, and a hundred-odd commands in quick succession is exactly what server
     * anti-spam kicks for - not a theoretical risk, it happened. This is the one place the scrape
     * is deliberately slow, and it cannot be set to zero.
     */
    private static final int DEFAULT_COMMAND_COOLDOWN = 40;
    private static final int MIN_COMMAND_COOLDOWN = 10;

    private static final int TIMEOUT = 60;             // waiting for a server-pushed screen
    private static final int SHORT_TIMEOUT = 12;       // "did anything change?" probe
    private static final int MAX_PAGE_STEPS = 40;      // a book this long means something is wrong
    private static final int MAX_PAGES = 8;            // dialog pages within one entry

    private static final int[] ENTRY_SLOTS = buildEntrySlots();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private enum State {
        IDLE, WAIT_GRID, CLICK_TAB, WAIT_TAB, PAGE_BACK, WAIT_PAGE_BACK,
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
    /** Ticks to let a fresh join settle before resuming - a command sent too early is wasted. */
    private static final int REJOIN_SETTLE = 60;

    private static int cooldown = DEFAULT_COMMAND_COOLDOWN;
    private static int sinceCommand;           // ticks since the last /journal went out
    private static int commandsSent;
    private static int navAt;                  // page reached while navigating back to gridPage
    private static String pageMarker = "";     // first entry's name on the current page
    private static Dialog lastDialog;
    private static String currentKey = "";     // marked as seen only once the entry is finished
    private static Path outFile;               // fixed at the start so partial saves land in it
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
        limit = Integer.MAX_VALUE;             // bare /journal walks the whole tab
        cooldown = DEFAULT_COMMAND_COOLDOWN;
        final String[] parts = c.split("\\s+");
        if (parts.length > 1) {
            try {
                limit = Math.max(1, Integer.parseInt(parts[1]));
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        // A second argument tunes the anti-spam gap: /journal 40 80 walks 40 entries with 4s
        // between reopens. Floored rather than trusted - zero would get you kicked.
        if (parts.length > 2) {
            try {
                cooldown = Math.max(MIN_COMMAND_COOLDOWN, Integer.parseInt(parts[2]));
            } catch (NumberFormatException ignored) {
                cooldown = DEFAULT_COMMAND_COOLDOWN;
            }
        }
        slotIndex = 0;
        dialogPage = 0;
        steps = 0;
        sinceCommand = 0;                      // the opening command has just gone out
        commandsSent = 1;
        gridPage = 0;
        navAt = 0;
        pageMarker = "";
        entries = new JsonArray();
        seen = new HashSet<>();
        kicks = 0;
        currentKey = "";
        startedAt = System.currentTimeMillis();
        // Chosen up front so every partial save through the run lands in the same file.
        outFile = Minecraft.getInstance().gameDirectory.toPath().resolve(MOD_ID)
                .resolve("journal-" + LocalDateTime.now().format(STAMP) + ".json");
        state = State.WAIT_GRID;
        wait = TIMEOUT;
        say(Component.literal((limit == Integer.MAX_VALUE
                        ? "scraping every page of the first tab"
                        : ("scraping up to " + limit + " entries"))
                        + " - leave the GUI alone; "
                        + String.format("%.1fs", cooldown / 20.0) + " between reopens to avoid a spam kick")
                .withStyle(ChatFormatting.GRAY));
        return true;
    }

    public static void tick(Minecraft mc) {
        if (state == State.IDLE || mc == null || mc.gui == null) {
            return;
        }
        sinceCommand++;

        // A KICK IS NOT THE END OF THE WALK. Losing the connection used to abandon the run and
        // throw away everything collected; now the progress is written out and the walk parks
        // until you are back in, then picks up at the entry it was on.
        final boolean connected = mc.getConnection() != null && mc.player != null;
        if (!connected) {
            if (state != State.WAIT_RECONNECT) {
                kicks++;
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
            case WAIT_GRID -> {
                if (gridOpen(mc)) {
                    state = State.CLICK_TAB;
                } else if (--wait <= 0) {
                    fail("the journal did not open");
                }
            }
            // Normalise: first tab, then page back to the start. Both detect "already there" by
            // nothing changing before the short timeout.
            case CLICK_TAB -> {
                pageMarker = firstEntryName(mc);
                clickSlot(mc, TAB_SLOT);
                state = State.WAIT_TAB;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_TAB -> {
                if (changed(mc)) {
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
                pageMarker = firstEntryName(mc);
                clickSlot(mc, PREV_SLOT);
                state = State.WAIT_PAGE_BACK;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_PAGE_BACK -> {
                if (changed(mc)) {
                    state = State.PAGE_BACK;          // moved, so there may be further back to go
                } else if (--wait <= 0) {
                    navAt = 0;                        // this is page 0
                    pageMarker = firstEntryName(mc);
                    state = (navAt < gridPage) ? State.NAV_FORWARD : State.OPEN_ENTRY;
                }
            }
            // Walk forward to the page we were on before the grid closed.
            case NAV_FORWARD -> {
                if (++steps > MAX_PAGE_STEPS) {
                    fail("could not return to page " + (gridPage + 1));
                    return;
                }
                pageMarker = firstEntryName(mc);
                clickSlot(mc, NEXT_SLOT);
                state = State.WAIT_NAV_FORWARD;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_NAV_FORWARD -> {
                if (changed(mc)) {
                    navAt++;
                    pageMarker = firstEntryName(mc);
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
                    finish(mc);
                    return;
                }
                pageMarker = firstEntryName(mc);
                clickSlot(mc, NEXT_SLOT);
                state = State.WAIT_GRID_PAGE;
                wait = SHORT_TIMEOUT;
            }
            case WAIT_GRID_PAGE -> {
                if (changed(mc)) {
                    gridPage++;
                    navAt = gridPage;
                    slotIndex = 0;
                    pageMarker = firstEntryName(mc);
                    state = State.OPEN_ENTRY;
                } else if (--wait <= 0) {
                    finish(mc);                       // no further pages in this tab
                }
            }
            case WAIT_RECONNECT -> {
                // Back in the world. Let the join settle, then rejoin the walk through the normal
                // reopen path so the command still respects the anti-spam gap.
                if (--wait <= 0) {
                    say(Component.literal("resuming at entry " + (entries.size() + 1))
                            .withStyle(ChatFormatting.GRAY));
                    sinceCommand = cooldown;      // do not make the first command wait needlessly
                    pageMarker = "";              // force a re-navigation: the page is unknown now
                    steps = 0;
                    state = State.REOPEN;
                }
            }
            case REOPEN -> {
                if (gridOpen(mc)) {
                    state = State.OPEN_ENTRY;
                } else if (mc.getConnection() == null) {
                    state = State.WAIT_RECONNECT;   // unreachable in practice: tick() catches a
                    wait = REJOIN_SETTLE;           // dropped connection first. Defensive only.
                } else if (sinceCommand < cooldown) {
                    mc.setScreenAndShow(null);    // idling on purpose: see DEFAULT_COMMAND_COOLDOWN
                } else {
                    mc.setScreenAndShow(null);
                    mc.getConnection().sendCommand(COMMAND);
                    sinceCommand = 0;
                    commandsSent++;
                    state = State.WAIT_REGRID;
                    wait = TIMEOUT;
                }
            }
            case WAIT_REGRID -> {
                if (gridOpen(mc)) {
                    // Reopened on the right page? Otherwise navigate back to it.
                    if (firstEntryName(mc).equals(pageMarker)) {
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

    private static AbstractContainerScreen<?> grid(Minecraft mc) {
        return mc.gui.screen() instanceof AbstractContainerScreen<?> s
                && s.getMenu().slots.size() == JOURNAL_SLOTS ? s : null;
    }

    private static boolean gridOpen(Minecraft mc) {
        return grid(mc) != null;
    }

    /** Has the grid moved since pageMarker was taken? The page's first entry is the fingerprint. */
    private static boolean changed(Minecraft mc) {
        return gridOpen(mc) && !firstEntryName(mc).equals(pageMarker);
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

    /** Entries are keyed by name AND level: the same name legitimately exists at two levels. */
    private static String keyOf(ItemStack stack) {
        final String name = plain(stack.getHoverName());
        String level = "";
        final ItemLore lore = stack.get(DataComponents.LORE);
        if (lore != null) {
            for (Component line : lore.lines()) {
                final String t = plain(line);
                if (t.startsWith("Enemy Level:")) {
                    level = t.substring("Enemy Level:".length()).trim();
                    break;
                }
            }
        }
        return name + "|" + level;
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
            state = State.REOPEN;
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
        currentKey = key;
        currentEntry = new JsonObject();
        currentEntry.addProperty("page", gridPage + 1);
        currentEntry.addProperty("slot", slot);
        currentEntry.addProperty("name", plain(stack.getHoverName()));
        final String level = key.substring(key.indexOf('|') + 1);
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
            if (!currentKey.isEmpty()) {
                seen.add(currentKey);
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
        state = State.REOPEN;
    }

    /** Writes what has been collected so far. Called on completion, on a kick, and periodically. */
    private static boolean save(boolean complete) {
        if (outFile == null) {
            return false;
        }
        final JsonObject root = new JsonObject();
        root.addProperty("scrapedAt", LocalDateTime.now().toString());
        root.addProperty("durationMs", System.currentTimeMillis() - startedAt);
        root.addProperty("entryCount", entries.size());
        root.addProperty("gridPages", gridPage + 1);
        root.addProperty("commandsSent", commandsSent);
        root.addProperty("disconnects", kicks);
        root.addProperty("complete", complete);
        root.add("entries", entries);
        try {
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, GSON.toJson(root));
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
        if (!save(true)) {
            state = State.IDLE;
            return;
        }

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
        say(Component.literal(String.format("%d entries over %d page%s in %.1fs%s -> ",
                        entries.size(), gridPage + 1, gridPage == 0 ? "" : "s", secs,
                        kicks == 0 ? "" : (" (survived " + kicks + " disconnect"
                                + (kicks == 1 ? "" : "s") + ")")))
                .withStyle(ChatFormatting.GREEN).append(link));
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
