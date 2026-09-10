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

    /**
     * Collector defaults compiled into the jar by the build, or empty in a plain source build.
     *
     * <p>WHY THESE EXIST. Without a URL a fresh install has no collector index to consult, so it
     * opens every entry rather than walking past the ones already collected - the slow path that
     * earns spam kicks. Defaulting them is what makes the mod useful to someone who just installed
     * it, rather than only to whoever set the config up by hand.
     *
     * <p>The token here is NOT a secret and is not treated as one: it ships in every copy of the
     * jar and anyone can read it out. It is a spam gate. What actually protects the collector is
     * server-side - per-address rate limiting, body and file caps, and a merge that cannot be made
     * to delete anything.
     */
    private static final Map<String, String> BAKED = readBaked();

    private Uploader() {
    }

    private static Map<String, String> readBaked() {
        final Map<String, String> out = new HashMap<>();
        try (java.io.InputStream in =
                     Uploader.class.getResourceAsStream("/journalscrape.collector.properties")) {
            if (in != null) {
                final java.util.Properties p = new java.util.Properties();
                p.load(in);
                for (String k : p.stringPropertyNames()) {
                    out.put(k.toLowerCase(), p.getProperty(k, "").trim());
                }
            }
        } catch (IOException e) {
            // A build without the resource is the normal source-build case, not an error.
        }
        return out;
    }

    /**
     * A URL setting with the baked-in default behind it: the config file wins, blank falls back to
     * what the build shipped, and "off" means off.
     *
     * <p>Blank cannot mean "disabled" any more now that there is something to fall back to, so
     * there has to be a way to say no explicitly - hence "off", the same word known-url already
     * used.
     */
    private static String urlSetting(Map<String, String> cfg, String key) {
        final String v = cfg.getOrDefault(key, "").trim();
        if (v.equalsIgnoreCase("off") || v.equalsIgnoreCase("none")) {
            return "";
        }
        return v.isBlank() ? BAKED.getOrDefault(key, "") : v;
    }

    /** Host of a URL, lowercased, or "" when it will not parse. */
    private static String host(String url) {
        try {
            final String h = URI.create(url).getHost();
            return h == null ? "" : h.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * The token to send with a request to {@code url}.
     *
     * <p>THE BAKED TOKEN IS PAIRED TO THE BAKED HOST. Someone who points upload-url at a collector
     * of their own and leaves the token line blank must not have this build's token sent to their
     * server - that would hand it to a third party who never had it. An explicitly configured token
     * is the user's own choice and goes wherever they aimed it.
     */
    private static String tokenFor(Map<String, String> cfg, String url) {
        final String own = cfg.getOrDefault("upload-token", "").trim();
        if (!own.isBlank()) {
            return own;
        }
        final String bakedHost = host(BAKED.getOrDefault("upload-url", ""));
        return (!bakedHost.isEmpty() && bakedHost.equals(host(url)))
                ? BAKED.getOrDefault("upload-token", "") : "";
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
                # THIS BUILD SHIPS WITH A COLLECTOR ALREADY SET UP. The three settings at the
                # bottom are blank on purpose: blank means "use the collector this build was
                # released with", so an [upload] button appears in chat when a scrape finishes
                # without you configuring anything. Put your own URL there to use a different
                # collector, or upload-url=off to turn uploading off completely.
                #
                # (Leaving them blank rather than filling them in also means a collector whose
                # address or token changes is fixed by updating the mod, not by editing this file.)
                #
                # The collector MERGES what it receives: entries are taken from whoever has read
                # the most of them, so uploading a partly-unlocked journal cannot overwrite a
                # fuller one already collected.

                # WHAT LEAVES YOUR MACHINE. Clicking [upload] sends the scrape file - journal
                # entries, their pages and drops, plus the run's own timings - and your in-game
                # name in an X-Player header, so a contribution can be attributed. Nothing is sent
                # without that click.
                #
                # ONE REQUEST IS NOT CLICK-GATED, AND IT IS ON BY DEFAULT: before a scrape starts,
                # the collector is asked what it already holds, so entries nobody can add to are
                # walked past without being opened - much faster, and far less likely to earn a
                # spam kick, which is why it defaults to on. It sends no name and nothing about
                # you, but it is a web request, so it reaches the collector from your IP address
                # like any other. Set known-url=off to stop it and always scrape everything.
                #
                # The upload token below is not a secret and is not treated as one - it ships
                # inside every copy of this mod. It only keeps random traffic off the endpoint.

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

                # The walk opens and closes a window several times a second, which flashes for
                # the whole run - unpleasant to watch and unsafe for anyone photosensitive. So the
                # screen is covered with a still panel showing the progress. overlay=false shows the
                # raw windows instead.

                overlay=true

                pause-every=10
                pause-seconds=3
                pause-step-seconds=1
                max-pause-seconds=15
                stop-key=none
                max-minutes=0

                # blank = the collector this build shipped with. "off" disables.
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
        final String up = uploadUrl();
        final int slash = up.lastIndexOf('/');
        return slash > 0 ? up.substring(0, slash) + "/known" : "";
    }

    /** Where a scrape is sent: the config file, or the collector this build shipped with. */
    static String uploadUrl() {
        return urlSetting(config(), "upload-url");
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
            final String token = tokenFor(config(), url);
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
        return !uploadUrl().isBlank();
    }

    /** Fire-and-forget upload of one scrape file. Safe to call from the client thread. */
    static void upload(Path file) {
        if (busy) {
            JournalScrape.say(Component.literal("an upload is already in flight")
                    .withStyle(ChatFormatting.YELLOW));
            return;
        }
        final Map<String, String> cfg = config();
        final String url = urlSetting(cfg, "upload-url");
        if (url.isBlank()) {
            JournalScrape.say(Component.literal("uploading is off (upload-url in config/"
                            + JournalScrape.MOD_ID + ".txt)").withStyle(ChatFormatting.RED));
            return;
        }
        if (!Files.isRegularFile(file)) {
            JournalScrape.say(Component.literal("nothing to upload: " + file.getFileName())
                    .withStyle(ChatFormatting.RED));
            return;
        }

        final String token = tokenFor(cfg, url);
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
