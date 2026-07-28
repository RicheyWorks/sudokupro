package com.xai.sudokupro.service;

import com.xai.sudokupro.model.User;
import com.xai.sudokupro.repository.GameRepository;
import com.xai.sudokupro.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Defect class: <b>an enforcement action whose only lasting effect was a log line</b> — the
 * same family as the analytics method that was a stub returning an empty map.
 *
 * <p>{@code AntiCheatEngine.flagPlayer} was named as though it recorded a moderation decision.
 * It halved the player's {@code cosmicDrip} and returned. Nothing anywhere recorded THAT the
 * player had been flagged: no column, no table, no event. The only trace was
 * {@code AntiCheatScheduler.flaggedPlayers}, a {@code HashMap} in one bean on one pod, which
 * a restart, a redeploy or a rolling update erased — and which the other replicas never saw
 * at all. So "flagged" meant "one process happens to remember, for now", while the visible
 * consequence (a drip balance quietly halved every 60 seconds, since the scheduler re-flags on
 * every pass) was applied to a real account with no record of why. A moderator asked "is this
 * player flagged, and since when?" had nowhere to look.
 *
 * <p>These tests pin the flag to durable storage and to the moderation lifecycle: it must
 * survive the process that issued it, accumulate, be queryable, and be clearable. They
 * deliberately do NOT touch {@code getCheatSuspicionScores()} or its threshold — that map is
 * the enforcement signal being consumed elsewhere, and this work is additive to it.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:flagging;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa",
    "spring.datasource.password=",
    "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.flyway.enabled=false",
    "sudokupro.ui.enabled=false"
})
class PlayerFlaggingTest {

    @Autowired private AntiCheatEngine antiCheatEngine;
    @Autowired private UserRepository userRepository;
    @Autowired private AnalyticsService analyticsService;
    @Autowired private GameRepository gameRepository;

    private User newPlayer(int cosmicDrip) {
        User u = new User(null, "flag" + Long.toString(
            ThreadLocalRandom.current().nextLong(1L << 44), 36));
        u.setCosmicDrip(cosmicDrip);
        return userRepository.saveAndFlush(u);
    }

    /**
     * A fresh engine over the same database is exactly what the next pod sees after a
     * restart or a rolling deploy. Before the fix it saw nothing: the flag existed only in
     * the memory of the process that made the decision.
     */
    @Test
    void aFlagOutlivesTheProcessThatIssuedIt() {
        User player = newPlayer(80);
        // The playerId handed to the engine in production is the USERNAME.
        String playerId = player.getUsername();

        antiCheatEngine.flagPlayer(playerId);

        AntiCheatEngine afterRestart =
            new AntiCheatEngine(analyticsService, userRepository, gameRepository);

        assertThat(afterRestart.isFlagged(playerId))
            .as("a restarted pod must still know this player is flagged")
            .isTrue();

        User reloaded = userRepository.findById(player.getId()).orElseThrow();
        assertThat(reloaded.getCheatFlagCount()).isEqualTo(1);
        assertThat(reloaded.getFirstFlaggedAt()).isNotNull();
        assertThat(reloaded.getLastFlaggedAt()).isNotNull();
    }

    /**
     * Repeat flags must accumulate, and the first-seen timestamp must not move. "How long has
     * this been going on" is the question a moderator actually asks, and overwriting the first
     * timestamp on every scheduler pass would answer it with "about a minute", always.
     */
    @Test
    void repeatFlagsAccumulateWithoutLosingTheFirstSighting() {
        User player = newPlayer(80);
        // The playerId handed to the engine in production is the USERNAME.
        String playerId = player.getUsername();

        antiCheatEngine.flagPlayer(playerId);
        var firstSeen = userRepository.findById(player.getId()).orElseThrow().getFirstFlaggedAt();

        antiCheatEngine.flagPlayer(playerId);
        antiCheatEngine.flagPlayer(playerId);

        User reloaded = userRepository.findById(player.getId()).orElseThrow();
        assertThat(reloaded.getCheatFlagCount()).isEqualTo(3);
        assertThat(reloaded.getFirstFlaggedAt()).isEqualTo(firstSeen);
    }

    /**
     * A flag nobody can query is a flag nobody can act on. This is the "is it actually wired"
     * half: the persisted state must be reachable through the repository, not just sitting in
     * a column.
     */
    @Test
    void flaggedPlayersAreQueryable() {
        User flagged = newPlayer(40);
        User clean = newPlayer(40);

        antiCheatEngine.flagPlayer(flagged.getUsername());

        assertThat(userRepository.findFlaggedPlayers())
            .extracting(User::getId)
            .contains(flagged.getId())
            .doesNotContain(clean.getId());
    }

    /**
     * The moderation lifecycle has two directions. {@code AntiCheatScheduler.clearFlaggedPlayer}
     * is the "this was a false positive" path; if it cleared only the in-memory suspicion and
     * left the durable flag set, an exonerated player would stay flagged forever in the record
     * that outlives the process.
     */
    @Test
    void clearingSuspicionAlsoClearsTheDurableFlag() {
        User player = newPlayer(60);
        // The playerId handed to the engine in production is the USERNAME.
        String playerId = player.getUsername();

        antiCheatEngine.flagPlayer(playerId);
        assertThat(antiCheatEngine.isFlagged(playerId)).isTrue();

        antiCheatEngine.clearPlayerSuspicion(playerId);

        assertThat(antiCheatEngine.isFlagged(playerId)).isFalse();
        User reloaded = userRepository.findById(player.getId()).orElseThrow();
        assertThat(reloaded.getCheatFlagCount()).isZero();
        assertThat(reloaded.getFirstFlaggedAt()).isNull();
        assertThat(reloaded.getLastFlaggedAt()).isNull();
    }

    /**
     * The pre-existing economic penalty must survive the change — this work adds a record of
     * the decision, it does not replace the consequence.
     */
    @Test
    void theCosmicDripPenaltyIsUnchanged() {
        User player = newPlayer(80);

        antiCheatEngine.flagPlayer(player.getUsername());

        assertThat(userRepository.findById(player.getId()).orElseThrow().getCosmicDrip())
            .isEqualTo(40);
    }

    /** Ids with no database row behind them must stay a no-op rather than blowing up. */
    @Test
    void nonPersistentPlayerIdsAreIgnored() {
        assertThatCode(() -> {
            antiCheatEngine.flagPlayer("anonymous");
            antiCheatEngine.flagPlayer("no-such-username-exists");
            antiCheatEngine.flagPlayer("");
            antiCheatEngine.flagPlayer(null);
        }).doesNotThrowAnyException();

        assertThat(antiCheatEngine.isFlagged("anonymous")).isFalse();
        assertThat(antiCheatEngine.isFlagged("no-such-username-exists")).isFalse();
    }
}
