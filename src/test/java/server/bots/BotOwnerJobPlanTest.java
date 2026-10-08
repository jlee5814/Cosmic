package server.bots;

import client.Character;
import client.Job;
import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotOwnerJobPlanTest {
    @BeforeAll
    static void primeJobTopologyBeforeStaticMocks() {
        for (Job job : Job.values()) BotCareerPlan.forTarget(job);
    }
    private BotEntry entry(Job job, int level) {
        Character bot = mock(Character.class);
        Character owner = mock(Character.class);
        when(bot.getJob()).thenReturn(job);
        when(bot.getLevel()).thenReturn(level);
        when(bot.getId()).thenReturn(254);
        when(owner.getPosition()).thenReturn(new Point());
        return new BotEntry(bot, owner, mock(ScheduledFuture.class));
    }

    private void plan(BotEntry entry) {
        entry.personality = BotPersonality.defaults().withOwnerPlannedJobs(Job.MAGICIAN, Job.CLERIC);
    }

    @Test
    void savedPlanSurvivesReloadWithoutEnablingLegacyCreationPlans() {
        BotPersonality old = BotPersonality.defaults().withPlannedJobs(Job.MAGICIAN, Job.CLERIC);
        assertFalse(BotPersonality.parse(old.serialize()).ownerJobPlan());
        assertFalse(BotPersonality.parse("v=1;pj1=200;pj2=230").ownerJobPlan());
        BotPersonality saved = old.withOwnerPlannedJobs(Job.MAGICIAN, Job.CLERIC);
        BotPersonality loaded = BotPersonality.parse(saved.serialize());
        assertTrue(loaded.ownerJobPlan());
        assertEquals(Job.MAGICIAN, loaded.plannedFirstJob());
        assertEquals(Job.CLERIC, loaded.plannedSecondJob());
        assertTrue(loaded.withPlannedJobs(Job.MAGICIAN, Job.CLERIC).ownerJobPlan());
        assertEquals(Job.BISHOP, BotPersonality.parse("v=1;pj1=200;pj2=230;ownerJobPlan=true").ownerJobGoal());
    }

    @ParameterizedTest
    @CsvSource({"BEGINNER,7,", "BEGINNER,8,MAGICIAN", "MAGICIAN,29,", "MAGICIAN,30,CLERIC",
            "CLERIC,69,", "CLERIC,70,PRIEST", "PRIEST,119,", "PRIEST,120,BISHOP",
            "BISHOP,120,", "WARRIOR,30,"})
    void respectsEveryMilestoneAndTheChosenBranch(Job current, int level, Job expected) {
        BotEntry entry = entry(current, level);
        plan(entry);
        assertEquals(expected, BotBuildManager.autoAdvanceTarget(entry, entry.bot));
    }

    @Test
    void planCommandIsWholeCommandAndDoesNotAdvanceImmediately() {
        assertTrue(BotChatManager.isBishopPlanCommand("Plan Bishop!"));
        assertFalse(BotChatManager.isBishopPlanCommand("i might plan bishop later"));
        assertFalse(BotChatManager.isBishopPlanCommand("bishop"));
        assertFalse(BotChatManager.isBishopPlanCommand(null));
        BotEntry entry = entry(Job.BEGINNER, 1);
        // Explicit career plans work even while another choice prompt is open.
        entry.pendingAction = "skill_tree_choice";
        BotConfigService store = mock(BotConfigService.class);
        BotManager manager = mock(BotManager.class);
        try (MockedStatic<BotConfigService> config = mockStatic(BotConfigService.class);
             MockedStatic<BotManager> bm = mockStatic(BotManager.class);
             MockedStatic<BotStarterKitManager> jobs = mockStatic(BotStarterKitManager.class)) {
            config.when(BotConfigService::getInstance).thenReturn(store);
            bm.when(BotManager::getInstance).thenReturn(manager);
            BotChatManager.handleChat(entry, "plan bishop");
            assertTrue(BotBuildManager.hasOwnerJobPlan(entry));
            verify(store).save(eq(254), contains("ownerJobPlan=true"));
            verify(manager).botReply(eq(entry), startsWith("saved!"));
            jobs.verify(() -> BotStarterKitManager.advanceJob(any(), any()), never());
        }
    }

    @Test
    void failedSaveDoesNotAcknowledgeOrInstallPlanAndWrongBranchIsRejected() {
        BotEntry entry = entry(Job.BEGINNER, 1);
        BotConfigService store = mock(BotConfigService.class);
        doThrow(new RuntimeException("database unavailable")).when(store).save(anyInt(), anyString());
        try (MockedStatic<BotConfigService> config = mockStatic(BotConfigService.class)) {
            config.when(BotConfigService::getInstance).thenReturn(store);
            assertTrue(BotBuildManager.setBishopPlan(entry).startsWith("couldn't save"));
            assertFalse(BotBuildManager.hasOwnerJobPlan(entry));
            clearInvocations(store);
            when(entry.bot.getJob()).thenReturn(Job.FP_WIZARD);
            assertTrue(BotBuildManager.setBishopPlan(entry).contains("another job path"));
            verifyNoInteractions(store);
        }
    }

    @Test
    void supervisedReloadReconcilesOnceAndRechecksManualAdvanceDuringDelay() {
        BotEntry entry = entry(Job.BEGINNER, 8);
        plan(entry);
        List<Runnable> deferred = new ArrayList<>();
        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(() -> BotManager.after(anyLong(), any(Runnable.class))).thenAnswer(call -> {
                deferred.add(call.getArgument(1));
                return null;
            });
            // Online owners no longer block their own saved plan, even on a fresh runtime entry.
            bm.when(() -> BotManager.hasOnlinePlayerOwner(entry)).thenReturn(true);
            BotBuildManager.maybeStartOverdueJobAdvance(entry, entry.bot);
            assertNull(BotBuildManager.buildJobPrompt(entry, entry.bot));
            BotBuildManager.maybeAdvanceOwnerJobPlan(entry, entry.bot);
            assertEquals(1, deferred.size());
            assertTrue(entry.plannedJobAdvancePending);
            when(entry.bot.getJob()).thenReturn(Job.MAGICIAN); // owner advanced before the callback
            try (MockedStatic<BotStarterKitManager> jobs = mockStatic(BotStarterKitManager.class)) {
                deferred.get(0).run();
                jobs.verify(() -> BotStarterKitManager.advanceJob(any(), any()), never());
            }
            assertFalse(entry.plannedJobAdvancePending);
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void usesExistingAdvancementAndInstructorFlow(boolean autopilot) {
        BotEntry entry = entry(Job.BEGINNER, 8);
        plan(entry);
        List<Runnable> deferred = new ArrayList<>();
        try (MockedStatic<BotManager> bm = mockStatic(BotManager.class)) {
            bm.when(() -> BotManager.after(anyLong(), any(Runnable.class))).thenAnswer(call -> {
                deferred.add(call.getArgument(1));
                return null;
            });
            BotBuildManager.maybeAdvanceOwnerJobPlan(entry, entry.bot);
        }
        try (MockedStatic<BotStarterKitManager> jobs = mockStatic(BotStarterKitManager.class);
             MockedStatic<BotAutopilotManager> ap = mockStatic(BotAutopilotManager.class)) {
            jobs.when(BotStarterKitManager::firstJobChoices).thenReturn(List.of(Job.MAGICIAN));
            jobs.when(() -> BotStarterKitManager.jobChangeNpcFor(Job.MAGICIAN))
                    .thenReturn(new BotStarterKitManager.JobChangeNpc(1032001, 101000003, "Ellinia"));
            ap.when(() -> BotAutopilotManager.isActive(entry)).thenReturn(autopilot);
            deferred.get(0).run();
            if (autopilot) {
                jobs.verify(() -> BotStarterKitManager.beginJobErrand(entry, Job.MAGICIAN));
                jobs.verify(() -> BotStarterKitManager.advanceJob(any(), any()), never());
            } else {
                jobs.verify(() -> BotStarterKitManager.advanceJob(entry, Job.MAGICIAN));
            }
            assertFalse(entry.plannedJobAdvancePending);
        }
    }
}
