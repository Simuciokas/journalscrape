package io.github.simuciokas.journalscrape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Sends a finished scrape to a collector, so several players' journals can be pooled.
 *
 * <p>The endpoint merges rather than replaces: players have unlocked different amounts, and an
 * entry is taken from whoever has read the most of it. Sending a thin journal therefore cannot
 * damage a fuller one already on the server.
 *
 * <p>ON A BACKGROUND THREAD, ALWAYS. An upload is a network round trip to a host that may be slow
 * or down; doing that on the client thread would freeze the game for its duration. The reply is
 * handed back through {@code Minecraft.execute} so chat is touched only from the client thread.
 */
final class Uploader {

    /** Written on first use, next to the mod's other config. */
    private static final Path CONFIG = Path.of("config", JournalScrape.MOD_ID + ".txt");

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static volatile boolean busy;

    private Uploader() {
    }

    private static Map<String, String> config() {
        final Map<String, String> cfg = new HashMap<>();
        try {
            if (!Files.isRegularFile(CONFIG)) {
                writeDefaults();
                return cfg;
            }
            for (String raw : Files.readAllLines(CONFIG)) {
                final String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                final int eq = line.indexOf('=');
                if (eq > 0) {
                    cfg.put(line.substring(0, eq).trim().toLowerCase(), line.substring(eq + 1).trim());
                }
            }
        } catch (IOException e) {
            // A missing or broken config just means uploads are not configured.
        }
        return cfg;
    }

    private static void writeDefaults() {
        final String text = """
                # journalscrape - upload settings
                #
                # Set both values and an [upload] button appears in chat when a scrape finishes.
                # Leaving upload-url empty disables uploading entirely; nothing is ever sent
                # without you clicking that button.
                #
                # The collector MERGES what it receives: entries are taken from whoever has read
                # the most of them, so uploading a partly-unlocked journal cannot overwrite a
                # fuller one already collected.

                # WHAT LEAVES YOUR MACHINE. Clicking [upload] sends the scrape file - journal
                # entries, their pages and drops, plus the run's own timings - and your in-game
                # name in an X-Player header, so a contribution can be attributed. Nothing is sent
                # without that click.
                #
                # One request is NOT click-gated: before a scrape starts, the collector is asked
                # what it already holds, so entries nobody can add to are walked past without being
                # opened - much faster, and far less likely to earn a spam kick. That request sends
                # no name, only the token if you set one, though it does reach the collector from
                # your IP address like any web request. Set known-url=off to stop it and always
                # scrape everything; it is otherwise derived from upload-url.

                # PACING. Reading an entry closes the journal, so the walk re-issues /journal
                # once per entry; sent flat out that reads as command spam and servers kick for it.
                # Rather than slowing every single entry down, the walk runs at full speed in
                # bursts and takes a breath between them: pause-seconds after every pause-every
                # entries. The pause grows by pause-step-seconds after each disconnect, up to
                # max-pause-seconds, and the value that worked is remembered for next time.
                # pause-every=0 turns pausing off, which is what earned the kicks.
                #
                # A long run does not have to be finished in one sitting: press ESCAPE to end it
                # cleanly, or set max-minutes to stop on a timer. Either way the file is written
                # and can be uploaded, and running /journal again carries on rather than starting
                # over - entries already collected are not re-read. stop-key names an ADDITIONAL
                # key if you want one; Escape always works.

                pause-every=10
                pause-seconds=3
                pause-step-seconds=1
                max-pause-seconds=15
                stop-key=none
                max-minutes=0

                upload-url=
                upload-token=
                known-url=
                """;
        try {
            Files.createDirectories(CONFIG.getParent());
            Files.writeString(CONFIG, text);
        } catch (IOException e) {
            // nothing useful to do; the button simply will not appear
        }
    }

    /**
     * Where to ask what the collector already knows. Derived from upload-url unless set outright,
     * so one setting is enough for the normal case.
     */
    static String knownUrl() {
        final Map<String, String> cfg = config();
        final String explicit = cfg.getOrDefault("known-url", "").trim();
        // "off" turns the check off while leaving uploading alone. Blank cannot mean that: blank is
        // the default, and the default is to derive it from upload-url.
        if (explicit.equalsIgnoreCase("off") || explicit.equalsIgnoreCase("none")) {
            return "";
        }
        if (!explicit.isBlank()) {
            return explicit;
        }
        final String up = cfg.getOrDefault("upload-url", "");
        final int slash = up.lastIndexOf('/');
        return slash > 0 ? up.substring(0, slash) + "/known" : "";
    }

    /**
     * key -> unlock tier for everything the collector holds, or null if it cannot be reached.
     *
     * <p>Never called from the client thread: this is a network round trip like the upload.
     */
    static JsonObject fetchKnown() {
        final String url = knownUrl();
        if (url.isBlank()) {
            return null;
        }
        try {
            final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET();
            final String token = config().getOrDefault("upload-token", "");
            if (!token.isBlank()) {
                b.header("X-Upload-Token", token);
            }
            final HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() / 100 != 2) {
                return null;
            }
            final JsonObject root = JsonParser.parseString(res.body()).getAsJsonObject();
            return root.has("tiers") ? root.getAsJsonObject("tiers") : null;
        } catch (Exception e) {
            return null;                          // no index just means nothing gets skipped
        }
    }

    /** One setting from the shared config file, for callers that are not about uploading. */
    static String setting(String key, String fallback) {
        final String v = config().getOrDefault(key, "");
        return v.isBlank() ? fallback : v;
    }

    static int setting(String key, int fallback) {
        try {
            return Integer.parseInt(setting(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static boolean configured() {
        final String url = config().get("upload-url");
        return url != null && !url.isBlank();
    }

    /** Fire-and-forget upload of one scrape file. Safe to call from the client thread. */
    static void upload(Path file) {
        if (busy) {
            JournalScrape.say(Component.literal("an upload is already in flight")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        final Map<String, String> cfg = config();
        final String url = cfg.getOrDefault("upload-url", "");
        if (url.isBlank()) {
            JournalScrape.say(Component.literal("no upload-url set in config/" + JournalScrape.MOD_ID + ".txt")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (!Files.isRegularFile(file)) {
            JournalScrape.say(Component.literal("nothing to upload: " + file.getFileName())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        final String token = cfg.getOrDefault("upload-token", "");
        final Minecraft mc = Minecraft.getInstance();
        final String player = (mc != null && mc.player != null)
                ? JournalScrape.plain(mc.player.getName()) : "anon";

        busy = true;
        JournalScrape.say(Component.literal("uploading " + file.getFileName() + "...")
                .withStyle(ChatFormatting.GRAY));

        Thread.ofVirtual().name("journalscrape-upload").start(() -> {
            String message;
            ChatFormatting colour;
            try {
                final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60))
                        .header("Content-Type", "application/json")
                        .header("X-Player", player)
                        .POST(HttpRequest.BodyPublishers.ofFile(file));
                if (!token.isBlank()) {
                    b.header("X-Upload-Token", token);
                }
                final HttpResponse<String> res = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() / 100 == 2) {
                    message = "upload accepted: " + summarise(res.body());
                    colour = ChatFormatting.GREEN;
                } else {
                    message = "upload rejected (" + res.statusCode() + "): " + trim(res.body(), 160);
                    colour = ChatFormatting.RED;
                }
            } catch (Exception e) {
                message = "upload failed: " + trim(String.valueOf(e), 160);
                colour = ChatFormatting.RED;
            }
            final String finalMessage = message;
            final ChatFormatting finalColour = colour;
            busy = false;
            if (mc != null) {
                // Back to the client thread: chat must not be touched from here.
                mc.execute(() -> JournalScrape.say(Component.literal(finalMessage).withStyle(finalColour)));
            }
        });
    }

    /** Pulls the interesting numbers out of the JSON reply without a parser. */
    private static String summarise(String body) {
        final String received = field(body, "received");
        final String after = field(body, "datasetAfter");
        final String added = field(body, "newEntries");
        if (received == null) {
            return trim(body, 160);
        }
        return received + " sent, " + (added == null ? "?" : added) + " new, "
                + (after == null ? "?" : after) + " known in total";
    }

    private static String field(String json, String name) {
        final int at = json.indexOf("\"" + name + "\"");
        if (at < 0) {
            return null;
        }
        final int colon = json.indexOf(':', at);
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        while (i < json.length() && (json.charAt(i) == ' ' || json.charAt(i) == '"')) {
            i++;
        }
        final int start = i;
        while (i < json.length() && "0123456789".indexOf(json.charAt(i)) >= 0) {
            i++;
        }
        return i > start ? json.substring(start, i) : null;
    }

    private static String trim(String s, int max) {
        final String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "...";
    }
}
