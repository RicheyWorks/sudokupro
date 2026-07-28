package com.xai.sudokupro.repository;

import com.xai.sudokupro.model.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The leaderboard-stats aggregate against a real JPA provider (H2).
 *
 * <p>This exists because the query could only fail against a real provider, and its only
 * caller was never wired to an endpoint while its service test mocked the repository — so a
 * {@code SELECT AVG(...) as x} multi-select declared to return {@code Map<String,Double>}
 * sat broken and invisible. JPQL returns {@code Object[]} for a multi-select (the {@code as}
 * aliases are cosmetic); binding that to a Map throws at execution. The fix is
 * {@code SELECT new map(...)}, and the only way to prove it is to run it.
 */
@DataJpaTest
@ActiveProfiles("test")
class LeaderboardStatsProjectionJpaTest {

    @Autowired private UserRepository userRepository;
    @Autowired private TestEntityManager entityManager;

    private User activePlayer(String name, int points, int duelWins, int cosmicDrip) {
        User u = new User(null, name);
        u.setPoints(points);
        u.setDuelWins(duelWins);
        u.setCosmicDrip(cosmicDrip);
        u.setLastLogin(LocalDateTime.now());
        return u;
    }

    @Test
    void statsAggregateBindsToAMapInsteadOfThrowing() {
        entityManager.persist(activePlayer("alpha", 100, 4, 10));
        entityManager.persist(activePlayer("bravo", 300, 8, 30));
        // A player who has not logged in recently must be excluded by the WHERE clause.
        User stale = activePlayer("stale", 9999, 99, 9999);
        stale.setLastLogin(LocalDateTime.now().minusDays(400));
        entityManager.persist(stale);
        entityManager.flush();
        entityManager.clear();

        Map<String, Double> stats = assertDoesNotThrow(() ->
            userRepository.getLeaderboardStatsSince(LocalDateTime.now().minusDays(30)));

        assertNotNull(stats);
        assertEquals(200.0, stats.get("avgPoints"), 0.0001,
            "average of 100 and 300 — the stale player must be excluded");
        assertEquals(6.0, stats.get("avgDuelWins"), 0.0001);
        assertEquals(20.0, stats.get("avgCosmicDrip"), 0.0001);
    }
}
