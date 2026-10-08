package server.bots;

import client.Character;
import client.Job;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotRosterPlanTest {
    @BeforeAll
    static void primeTopology() {
        for (Job job : Job.values()) BotCareerPlan.forTarget(job);
    }

    private BotEntry entry(Job current, Job goal, int level, int target) {
        Character bot = mock(Character.class);
        when(bot.getJob()).thenReturn(current);
        when(bot.getLevel()).thenReturn(level);
        BotEntry entry = new BotEntry(bot, bot, mock(ScheduledFuture.class));
        entry.personality = BotPersonality.defaults().withOwnerCareer(BotCareerPlan.forTarget(goal), target, "Quest farmer A01");
        return entry;
    }

    @ParameterizedTest
    @CsvSource({"IL_ARCHMAGE,MAGICIAN,30,IL_WIZARD", "IL_ARCHMAGE,IL_WIZARD,70,IL_MAGE",
            "IL_ARCHMAGE,IL_MAGE,120,IL_ARCHMAGE", "BISHOP,PRIEST,120,BISHOP",
            "PRIEST,CLERIC,70,PRIEST", "PRIEST,PRIEST,120,", "CLERIC,CLERIC,70,",
            "SPEARMAN,WARRIOR,30,SPEARMAN", "SPEARMAN,SPEARMAN,70,",
            "ASSASSIN,THIEF,30,ASSASSIN", "BANDIT,THIEF,30,BANDIT", "ASSASSIN,BANDIT,30,",
            "BEGINNER,BEGINNER,10,", "BEGINNER,BEGINNER,30,"})
    void careersRespectTerminalJobsAndBranchChoices(Job goal, Job current, int level, Job expected) {
        BotEntry entry = entry(current, goal, level, 0);
        assertEquals(expected, BotBuildManager.autoAdvanceTarget(entry, entry.bot));
    }

    @Test
    void savedRosterRoundTripsTargetsRolesAndWeaponBranch() {
        BotPersonality planned = BotPersonality.defaults().withOwnerCareer(BotCareerPlan.forTarget(Job.BANDIT), 35,
                "Quest farmer C09; dagger / storage");
        BotPersonality loaded = BotPersonality.parse(planned.serialize());
        assertEquals(Job.THIEF, loaded.plannedFirstJob());
        assertEquals(Job.BANDIT, loaded.plannedSecondJob());
        assertEquals(Job.BANDIT, loaded.ownerJobGoal());
        assertEquals(35, loaded.trainingLevelTarget());
        assertEquals(planned.rosterRole(), loaded.rosterRole());
        assertEquals(planned.serialize(), loaded.serialize());
    }

    @Test
    void chatSupportsRosterCareersWithoutReadingOrdinaryConversationAsCommands() {
        assertEquals(Job.IL_ARCHMAGE, BotChatManager.matchJobPlan("plan il archmage"));
        assertEquals(Job.PRIEST, BotChatManager.matchJobPlan("plan priest"));
        assertEquals(Job.SPEARMAN, BotChatManager.matchJobPlan("plan spearman"));
        assertEquals(Job.ASSASSIN, BotChatManager.matchJobPlan("plan assassin"));
        assertEquals(Job.BANDIT, BotChatManager.matchJobPlan("plan bandit"));
        assertEquals(Job.BEGINNER, BotChatManager.matchJobPlan("plan beginner"));
        assertNull(BotChatManager.matchJobPlan("i might plan bishop later"));
        assertNull(BotChatManager.matchJobPlan("plan supergm"));
    }

    @Test
    void trainingStopsAtTargetWithoutBlockingAnOverdueLegalJobAdvance() {
        BotEntry entry = entry(Job.CLERIC, Job.CLERIC, 34, 35);
        assertFalse(BotTrainingPlan.complete(entry, entry.bot));
        when(entry.bot.getLevel()).thenReturn(35);
        assertTrue(BotTrainingPlan.complete(entry, entry.bot));
        entry.grinding = true;
        BotManager manager = mock(BotManager.class);
        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(BotManager::getInstance).thenReturn(manager);
            BotTrainingPlan.stopIfComplete(entry, entry.bot);
            verify(manager).issueStop(entry);
        }
        when(entry.bot.getJob()).thenReturn(Job.MAGICIAN);
        assertFalse(BotTrainingPlan.complete(entry, entry.bot)); // finish Cleric before parking
        BotEntry beginner = entry(Job.BEGINNER, Job.BEGINNER, 30, 30);
        assertTrue(BotTrainingPlan.complete(beginner, beginner.bot));
        BotEntry main = entry(Job.IL_ARCHMAGE, Job.IL_ARCHMAGE, 150, 0);
        assertFalse(BotTrainingPlan.complete(main, main.bot)); // 120+ farmer keeps farming
    }

    @Test
    void delegatedThiefPlanSelectsItsWeaponLineEvenWithOwnerOnline() {
        BotEntry entry = entry(Job.THIEF, Job.BANDIT, 10, 35);
        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(() -> BotManager.isAutopilotActive(entry)).thenReturn(true);
            bm.when(() -> BotManager.hasOnlinePlayerOwner(entry)).thenReturn(true);
            assertNull(BotBuildManager.buildSpVariantPrompt(entry, entry.bot));
            assertEquals("dagger", entry.spVariant);
        }
    }
}
