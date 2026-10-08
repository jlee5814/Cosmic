package server.bots;

import client.Character;

/** Training targets stop farming; they do not freeze XP or change player leveling mechanics. */
final class BotTrainingPlan {
    private BotTrainingPlan() {}

    static boolean complete(BotEntry entry, Character bot) {
        BotPersonality p = entry.personality;
        return p != null && p.trainingLevelTarget() > 0 && bot.getLevel() >= p.trainingLevelTarget()
                && BotBuildManager.autoAdvanceTarget(entry, bot) == null;
    }

    static void stopIfComplete(BotEntry entry, Character bot) {
        if (complete(entry, bot) && (entry.grinding || BotAutopilotManager.isActive(entry))) {
            BotManager.getInstance().issueStop(entry);
        }
    }
}
