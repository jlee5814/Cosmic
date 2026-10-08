package server.bots;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

/** Restore explicitly assigned owner rosters independently of the public population scheduler. */
final class BotRosterService {
    private static final Logger log = LoggerFactory.getLogger(BotRosterService.class);

    private BotRosterService() {}

    static void start() {
        Thread.ofPlatform().daemon().name("bot-owner-roster-start").start(() -> {
            List<Integer> ids = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT b.bot_char_id,b.config FROM bot_config b "
                                 + "JOIN bot_owners o ON o.bot_char_id=b.bot_char_id ORDER BY b.bot_char_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BotPersonality p = BotPersonality.parse(rs.getString("config"));
                    if (!p.rosterRole().isBlank() && p.ownerJobPlan()
                            && BotCareerPlan.forTarget(p.ownerJobGoal()) != null) ids.add(rs.getInt("bot_char_id"));
                }
            } catch (Exception e) {
                log.error("Could not load owner bot roster", e);
                return;
            }
            int spawned = 0;
            for (int id : ids) {
                try {
                    if (BotManager.getInstance().spawnManagedBot(id)) spawned++;
                } catch (RuntimeException e) {
                    log.warn("Could not restore roster bot {}", id, e);
                }
            }
            log.info("Owner roster restored: {} spawned of {} configured", spawned, ids.size());
        });
    }
}
