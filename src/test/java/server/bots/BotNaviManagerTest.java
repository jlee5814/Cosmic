package server.bots;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotNaviManagerTest {
    @Test
    void shouldRecognizeNaviCommandsOnlyWhenNaviLeads() {
        assertTrue(BotNaviManager.isNaviCommand("navi email"));
        assertTrue(BotNaviManager.isNaviCommand("  Navi, check my email?"));
        assertTrue(BotNaviManager.isNaviCommand("navi"));
        assertFalse(BotNaviManager.isNaviCommand("navigate to henesys"));
        assertFalse(BotNaviManager.isNaviCommand("hey navi email"));
        assertFalse(BotNaviManager.isNaviCommand(null));
    }

    @Test
    void shouldMapEmailPhrasingsToTheEmailTool() {
        for (String phrasing : List.of("navi email", "navi check my email?", "navi any new emails",
                "navi inbox", "NAVI read mail", "navi, check email!", "navi check my new mail")) {
            assertEquals(BotNaviManager.EMAIL_TOOL, BotNaviManager.toolFor(phrasing), phrasing);
        }
    }

    @Test
    void shouldNotGuessAToolForUnknownRequests() {
        assertNull(BotNaviManager.toolFor("navi"));
        assertNull(BotNaviManager.toolFor("navi send email to vera"));
        assertNull(BotNaviManager.toolFor("navi delete my email"));
        assertNull(BotNaviManager.toolFor("email"));
    }

    @Test
    void shouldWhisperServiceLinesCappedAndTrimmed() {
        String body = "4 unread in primary, newest first:\n a: one \n\nb: two\nc: three\nd: four\ne: five\n";
        assertEquals(List.of("4 unread in primary, newest first:", "a: one", "b: two", "c: three", "d: four"),
                BotNaviManager.resultLines(200, body, null));
    }

    @Test
    void shouldExplainFailuresWithoutEchoingErrorText() {
        List<String> unreachable = BotNaviManager.resultLines(0, null, new IOException("secret detail"));
        assertEquals(1, unreachable.size());
        assertFalse(unreachable.get(0).contains("secret"));
        assertEquals(List.of("the navi service rejected this server's token"),
                BotNaviManager.resultLines(401, "unauthorized\n", null));
        assertEquals(List.of("navi came back empty"), BotNaviManager.resultLines(200, "\n\n", null));
    }
}
