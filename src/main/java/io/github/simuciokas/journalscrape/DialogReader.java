package io.github.simuciokas.journalscrape;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.dialog.DialogScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.MultiActionDialog;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * Turns one dialog page into JSON, and presses its "Next" button.
 *
 * <p>Everything here reads the {@link Dialog} record rather than the rendered screen, so the text
 * is exactly what the server sent - no font measuring, no scraping of pixels.
 */
final class DialogReader {

    /** Key event standing in for a click: {@code onPress} needs an InputWithModifiers in 26.2. */
    private static final KeyEvent ENTER = new KeyEvent(257, 28, 0);

    private DialogReader() {
    }

    static JsonObject read(Dialog dialogObj) {
        final JsonObject page = new JsonObject();
        if (dialogObj == null) {
            page.addProperty("error", "no dialog on this screen");
            return page;
        }
        final Dialog dialog = dialogObj;
        page.addProperty("title", JournalScrape.plain(dialog.common().title()));

        final JsonArray body = new JsonArray();
        final JsonArray drops = new JsonArray();
        for (DialogBody element : dialog.common().body()) {
            if (!(element instanceof PlainMessage pm)) {
                continue;
            }
            body.add(JournalScrape.plain(pm.contents()));
            collectHovers(pm.contents(), drops, 0);
        }
        page.add("body", body);
        page.add("drops", drops);

        if (dialogObj instanceof MultiActionDialog mad) {
            for (ActionButton action : mad.actions()) {
                if (!JournalScrape.plain(action.button().label()).contains("Next")) {
                    continue;
                }
                // The BUTTON WIDGET's active flag is always true - a dead end shows up here, as an
                // action that is not present. Getting this wrong makes the walk re-read the last
                // page forever.
                page.addProperty("hasNext", action.action().isPresent());
                action.button().tooltip().ifPresent(t ->
                        page.addProperty("nextTip", JournalScrape.plain(t)));
            }
        }
        if (!page.has("hasNext")) {
            page.addProperty("hasNext", false);
        }
        return page;
    }

    /**
     * Every component carrying a ShowItem hover, with the server's loot definition pulled out.
     * That payload lives in the item's custom_data as a JSON string under "loot:loot.item_data";
     * it is kept verbatim so the consumer can parse it without this mod pinning a schema.
     */
    private static void collectHovers(Component comp, JsonArray into, int depth) {
        if (comp == null || depth > 8) {
            return;
        }
        // getHoverEvent(), NOT the record-style hoverEvent() - the wrong name compiles nowhere but
        // fails silently through reflection, and reports "no hovers" on a page full of them.
        final HoverEvent hover = comp.getStyle().getHoverEvent();
        if (hover instanceof HoverEvent.ShowItem show) {
            final String name = JournalScrape.plain(comp);
            if (!name.isEmpty()) {
                final JsonObject drop = new JsonObject();
                drop.addProperty("name", name);
                final JsonArray tip = tooltip(show);
                if (tip != null && !tip.isEmpty()) {
                    drop.add("tooltip", tip);
                }
                final String dump = String.valueOf(show.item());
                final int at = dump.indexOf("loot.item_data");
                if (at >= 0) {
                    final int start = dump.indexOf('{', at);
                    if (start >= 0) {
                        drop.addProperty("lootData", balanced(dump, start));
                    }
                } else if (dump.contains("loot.item_id")) {
                    drop.addProperty("kind", "material");
                }
                into.add(drop);
            }
        }
        final List<Component> siblings = comp.getSiblings();
        for (Component child : siblings) {
            collectHovers(child, into, depth + 1);
        }
    }

    /**
     * The item's tooltip AS THE PLAYER SEES IT.
     *
     * <p>The hover carries a full item, so rather than inventing a presentation from the loot data
     * we ask the game to render the same lines it would show under the cursor - name, stats,
     * flavour - each with its own colour. That way the viewer can show something a player already
     * knows how to read instead of a table in a format nobody has seen before.
     *
     * <p>The server pads its tooltips with private-use glyphs that act as icons and spacers. They
     * cannot render outside the game, so they are stripped; a line left empty is kept once as a
     * blank, and runs of blanks collapse, which preserves the shape without the noise.
     */
    private static JsonArray tooltip(HoverEvent.ShowItem show) {
        final Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.level == null || mc.player == null) {
            return null;
        }
        try {
            final ItemStack stack = show.item().create();
            final List<Component> lines =
                    stack.getTooltipLines(Item.TooltipContext.of(mc.level), mc.player, TooltipFlag.NORMAL);
            final JsonArray out = new JsonArray();
            boolean lastBlank = false;
            for (Component line : lines) {
                final String text = line.getString().replaceAll("[^\\x20-\\x7E]", "");
                final boolean blank = text.isBlank();
                if (blank && lastBlank) {
                    continue;
                }
                lastBlank = blank;
                final JsonObject row = new JsonObject();
                row.addProperty("t", blank ? "" : stripTrailing(text));
                if (!blank) {
                    // ALWAYS as runs, even when there is only one. The array is what tells a
                    // reader that the colours were resolved per segment; a bare colour means the
                    // row came from the old capture, where the recorded colour was the outer
                    // component's - and for item lore that is vanilla's placeholder purple, not
                    // anything the server chose.
                    final JsonArray runs = runs(line);
                    if (!runs.isEmpty()) {
                        row.add("r", runs);
                    }
                }
                out.add(row);
            }
            while (!out.isEmpty() && out.get(out.size() - 1).getAsJsonObject()
                    .get("t").getAsString().isEmpty()) {
                out.remove(out.size() - 1);          // trailing spacers add nothing
            }
            return out;
        } catch (Exception e) {
            return null;                              // never let a tooltip break the scrape
        }
    }

    /**
     * One line split into its coloured pieces.
     *
     * <p>THE LINE'S OWN STYLE IS NOT THE LINE'S COLOUR. The server builds a lore line as a base
     * component carrying a colour with differently coloured children appended to it, so reading
     * {@code line.getStyle().getColor()} paints the whole line in the base colour and throws away
     * every highlight - which made every stat, requirement and flavour line render identically.
     * Visiting the tree resolves each piece's style against its parents, exactly as the game does
     * when it draws the tooltip.
     */
    private static JsonArray runs(Component line) {
        final JsonArray runs = new JsonArray();
        try {
            line.visit((style, piece) -> {
                final String text = piece.replaceAll("[^\\x20-\\x7E]", "");
                if (text.isEmpty()) {
                    return Optional.empty();
                }
                final String colour = style.getColor() == null ? null : style.getColor().serialize();
                // Adjacent pieces in the same colour are one run: the server splits lines on every
                // style change, including ones that do not change the colour.
                if (!runs.isEmpty()) {
                    final JsonObject last = runs.get(runs.size() - 1).getAsJsonObject();
                    final String lastColour = last.has("c") ? last.get("c").getAsString() : null;
                    if (Objects.equals(lastColour, colour)) {
                        last.addProperty("t", last.get("t").getAsString() + text);
                        return Optional.empty();
                    }
                }
                final JsonObject run = new JsonObject();
                run.addProperty("t", text);
                if (colour != null) {
                    run.addProperty("c", colour);
                }
                runs.add(run);
                return Optional.empty();
            }, Style.EMPTY);
        } catch (Exception ignored) {
            // an uncoloured line is fine
        }
        if (!runs.isEmpty()) {
            final JsonObject last = runs.get(runs.size() - 1).getAsJsonObject();
            last.addProperty("t", stripTrailing(last.get("t").getAsString()));
        }
        return runs;
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    /** The JSON object starting at {@code from}, cut at its matching brace rather than a length. */
    private static String balanced(String s, int from) {
        int depth = 0;
        for (int i = from; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(from, i + 1);
                }
            }
        }
        return s.substring(from);             // truncated rather than lost
    }

    /** Presses "Next". The widget lives nested in a layout, so the whole tree is searched. */
    static boolean pressNext(DialogScreen<?> screen) {
        // onPress lives on AbstractButton, not AbstractWidget, and takes an InputWithModifiers.
        final AbstractWidget widget = find(screen, "Next", 0);
        if (!(widget instanceof AbstractButton button)) {
            return false;
        }
        button.onPress(ENTER);
        return true;
    }

    private static AbstractWidget find(GuiEventListener node, String label, int depth) {
        if (depth > 6 || !(node instanceof ContainerEventHandler parent)) {
            return null;
        }
        for (GuiEventListener child : parent.children()) {
            if (child instanceof AbstractWidget w
                    && JournalScrape.plain(w.getMessage()).contains(label)) {
                return w;
            }
            final AbstractWidget deep = find(child, label, depth + 1);
            if (deep != null) {
                return deep;
            }
        }
        return null;
    }
}
