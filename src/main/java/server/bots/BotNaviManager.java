package server.bots;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Owner Navi tools. The owner whispers one of their bots ("navi email"); the bot asks the local
 * Navi service (tools/navi) for a private result and whispers it back.
 *
 * <p>Privacy rules:
 * <ul>
 *   <li>Only the bot's registered owner can use it, and only by whisper. A navi command in map or
 *       party chat gets a nudge to whisper instead and never reaches the service.</li>
 *   <li>Results go straight to the whispering owner, not through {@code entry.replyChannel}, so a
 *       later map command can't redirect private text into map chat.</li>
 *   <li>Result text is never logged.</li>
 * </ul>
 * Credentials live in the service on the host; the game server only holds {@code NAVI_TOKEN}.
 */
public final class BotNaviManager {
    private static final Logger log = LoggerFactory.getLogger(BotNaviManager.class);

    private static final Pattern NAVI_PATTERN = Pattern.compile("^navi\\b\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^(?:(?:check|read|any)\\s+)?(?:(?:my|new)\\s+)*(?:e-?mails?|inbox|mail)$", Pattern.CASE_INSENSITIVE);
    static final String EMAIL_TOOL = "/v1/email";
    static final int MAX_LINES = 5;

    private static final String ENDPOINT = envOr("NAVI_ENDPOINT", "http://host.docker.internal:8790");
    private static final String TOKEN = envOr("NAVI_TOKEN", "");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    private static final List<String> ACKS = List.of("on it", "checking, one sec", "sure, looking now");

    /** Owners with a request in flight; one at a time each so repeated whispers don't stack. */
    private static final Set<Integer> inflight = ConcurrentHashMap.newKeySet();

    private BotNaviManager() {}

    static boolean isNaviCommand(String message) {
        return message != null && NAVI_PATTERN.matcher(message.strip()).matches();
    }

    /** Service path for a navi command, or null when the command isn't one Navi knows. */
    static String toolFor(String message) {
        if (message == null) {
            return null;
        }
        Matcher m = NAVI_PATTERN.matcher(message.strip());
        if (!m.matches()) {
            return null;
        }
        String request = m.group(1).replaceAll("^[\\p{Punct}\\s]+|[\\p{Punct}\\s]+$", "");
        return EMAIL_PATTERN.matcher(request).matches() ? EMAIL_TOOL : null;
    }

    /** Lines to whisper for a finished service call. Never echoes exception text. */
    static List<String> resultLines(int status, String body, Throwable error) {
        if (error != null) {
            return List.of("can't reach the navi service, is it running on the host?");
        }
        if (status == 401) {
            return List.of("the navi service rejected this server's token");
        }
        List<String> lines = (body == null ? "" : body).lines()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .limit(MAX_LINES)
                .toList();
        return lines.isEmpty() ? List.of("navi came back empty") : lines;
    }

    /** Map or party chat: nudge once (first bot only) and never call the service. */
    static void declineOutsideWhisper(BotEntry entry) {
        if (BotManager.getInstance().isFirstBotEntry(entry)) {
            BotManager.after(BotManager.randMs(500, 900),
                    () -> BotManager.getInstance().botReply(entry, "whisper me navi stuff, it's private"));
        }
    }

    /** Whisper entry point. The caller has already checked the speaker is the registered owner. */
    static void handleWhisper(BotEntry entry, Character owner, String message) {
        Character bot = entry.bot;
        String tool = toolFor(message);
        if (tool == null) {
            whisperLater(bot, owner, List.of("navi can do: navi email"), BotManager.randMs(400, 800));
            return;
        }
        if (TOKEN.isEmpty()) {
            whisperLater(bot, owner, List.of("navi isn't set up on this server yet (no NAVI_TOKEN)"),
                    BotManager.randMs(400, 800));
            return;
        }
        if (!inflight.add(owner.getId())) {
            whisperLater(bot, owner, List.of("still checking, one sec"), BotManager.randMs(300, 600));
            return;
        }
        whisperLater(bot, owner, List.of(BotManager.randomReply(ACKS)), BotManager.randMs(400, 900));

        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + tool))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + TOKEN)
                // The service decides whose mailbox this is; owning a bot isn't enough.
                .header("X-Navi-Owner", owner.getName())
                .GET()
                .build();
        HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    inflight.remove(owner.getId());
                    if (error != null) {
                        log.info("navi {} for {} failed: {}", tool, owner.getName(), error.getClass().getSimpleName());
                    } else {
                        log.info("navi {} for {} -> HTTP {}", tool, owner.getName(), response.statusCode());
                    }
                    List<String> lines = error != null
                            ? resultLines(0, null, error)
                            : resultLines(response.statusCode(), response.body(), null);
                    whisperLater(bot, owner, lines, BotManager.randMs(600, 1200));
                });
    }

    private static void whisperLater(Character bot, Character owner, List<String> lines, long firstDelayMs) {
        long delay = firstDelayMs;
        for (String line : lines) {
            BotManager.after(delay, () -> whisper(bot, owner, line));
            delay += BotManager.randMs(700, 1300);
        }
    }

    private static void whisper(Character bot, Character owner, String text) {
        if (owner.getClient() == null || !owner.isLoggedinWorld() || bot.getClient() == null) {
            return;
        }
        owner.sendPacket(PacketCreator.getWhisperReceive(
                bot.getName(), bot.getClient().getChannel() - 1, false, BotManager.sanitizeChat(text)));
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.strip();
    }
}
