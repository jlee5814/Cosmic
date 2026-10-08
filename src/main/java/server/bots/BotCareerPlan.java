package server.bots;

import client.Job;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Owner career goals use the same explorer job topology as ordinary bot advancement. */
record BotCareerPlan(Job goal, List<Job> path) {
    private static final Map<Job, BotCareerPlan> plans = new ConcurrentHashMap<>();

    static BotCareerPlan forTarget(Job goal) {
        if (goal == null) return null;
        return plans.computeIfAbsent(goal, BotCareerPlan::build);
    }

    private static BotCareerPlan build(Job goal) {
        if (goal == Job.BEGINNER) return new BotCareerPlan(goal, List.of(Job.BEGINNER));
        for (Job first : BotStarterKitManager.firstJobChoices()) {
            if (goal == first) return new BotCareerPlan(goal, List.of(Job.BEGINNER, first));
            for (Job second : BotStarterKitManager.secondJobChoices(first)) {
                List<Job> path = new ArrayList<>(List.of(Job.BEGINNER, first, second));
                if (goal == second) return new BotCareerPlan(goal, List.copyOf(path));
                Job third = BotStarterKitManager.thirdJobOf(second);
                if (third == null) continue;
                path.add(third);
                if (goal == third) return new BotCareerPlan(goal, List.copyOf(path));
                Job fourth = BotStarterKitManager.fourthJobOf(third);
                if (fourth == null) continue;
                path.add(fourth);
                if (goal == fourth) return new BotCareerPlan(goal, List.copyOf(path));
            }
        }
        return null;
    }

    Job first() { return path.size() > 1 ? path.get(1) : null; }
    Job second() { return path.size() > 2 ? path.get(2) : null; }

    Job next(Job current) {
        int index = path.indexOf(current);
        return index >= 0 && index + 1 < path.size() ? path.get(index + 1) : null;
    }

    static Job parseTarget(String text) {
        if (text == null) return null;
        String normalized = text.strip().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
        Job alias = switch (normalized) {
            case "mage", "magician" -> Job.MAGICIAN;
            case "il", "i/l", "il archmage", "i/l archmage", "ice lightning", "ice/lightning" -> Job.IL_ARCHMAGE;
            default -> null;
        };
        if (alias != null) return alias;
        for (Job job : Job.values()) {
            if (job.name().toLowerCase(Locale.ROOT).replace('_', ' ').equals(normalized)
                    && forTarget(job) != null) return job;
        }
        return null;
    }
}
