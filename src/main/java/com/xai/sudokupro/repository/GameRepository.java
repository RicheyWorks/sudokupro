package com.xai.sudokupro.repository;

import com.xai.sudokupro.model.SudokuBoard;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Cosmic vault of SudokuPro's game states.
 * Stores and queries SudokuBoard instances with galactic JPA precision—tracking chaos, drip, duels, and retention.
 */
public interface GameRepository extends JpaRepository<SudokuBoard, Long> {

    @EntityGraph(attributePaths = {"replayHistory"})
    @Query("SELECT b FROM SudokuBoard b WHERE b.chaosMode = true ORDER BY b.solveTime DESC")
    @Cacheable(value = "chaosModeGames", key = "#pageable.pageNumber")
    List<SudokuBoard> findChaosModeGames(Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.mirrorMode = true AND b.hintCount <= :maxHints ORDER BY b.solveTime ASC")
    @Cacheable(value = "fastMirrorGames", key = "#maxHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findFastMirrorGames(@Param("maxHints") int maxHints, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.gameId = :gameId")
    @Cacheable(value = "gameById", key = "#gameId")
    SudokuBoard findByGameId(@Param("gameId") String gameId);

    @Query("SELECT b FROM SudokuBoard b WHERE b.playerId = :playerId ORDER BY b.startTime DESC")
    List<SudokuBoard> findByPlayerId(@Param("playerId") String playerId, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.cosmicDripLevel >= :minDrip ORDER BY b.cosmicDripLevel DESC, b.solveTime ASC")
    @Cacheable(value = "cosmicDripGames", key = "#minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findCosmicDripGames(@Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND b.isSolved = true ORDER BY b.solveTime ASC")
    @Cacheable(value = "fastestSolves", key = "#start + '-' + #end + '-' + #pageable.pageNumber")
    List<SudokuBoard> findFastestSolvesInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.hintCount = 0 AND b.usedUndo = false AND b.isSolved = true ORDER BY b.solveTime ASC")
    @Cacheable(value = "perfectClears", key = "#pageable.pageNumber")
    List<SudokuBoard> findPerfectClears(Pageable pageable);

    @EntityGraph(attributePaths = {"replayHistory"})
    @Query("SELECT b FROM SudokuBoard b WHERE SIZE(b.replayHistory) >= :minMoves AND b.cosmicDripLevel > 0 ORDER BY SIZE(b.replayHistory) DESC")
    @Cacheable(value = "longCosmicGames", key = "#minMoves + '-' + #pageable.pageNumber")
    List<SudokuBoard> findLongCosmicGames(@Param("minMoves") int minMoves, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT b.* FROM sudoku_board b WHERE b.start_time > :since AND b.is_solved = false " +
           "ORDER BY b.time_limit_seconds - EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - b.start_time)) ASC", nativeQuery = true)
    @Cacheable(value = "activeUnfinishedGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findActiveUnfinishedGames(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.hintCount >= :minHints AND b.isSolved = false ORDER BY b.hintCount DESC")
    @Cacheable(value = "hintHeavyStrugglers", key = "#minHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findHintHeavyStrugglers(@Param("minHints") int minHints, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.chaosMode = true AND b.cosmicDripLevel >= :minDrip " +
           "ORDER BY b.cosmicDripLevel DESC, SIZE(b.replayHistory) DESC")
    @Cacheable(value = "activeChaosDripMasters", key = "#since + '-' + #minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findActiveChaosDripMasters(@Param("since") LocalDateTime since, @Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND b.mirrorMode = true AND b.isSolved = true " +
           "ORDER BY (b.solveTime.toSeconds() / SIZE(b.replayHistory)) ASC")
    @Cacheable(value = "efficientMirrorSolves", key = "#start + '-' + #end + '-' + #pageable.pageNumber")
    List<SudokuBoard> findEfficientMirrorSolvesInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT b.* FROM sudoku_board b " +
           "WHERE b.start_time > :since AND b.is_solved = true " +
           "AND EXISTS (SELECT 1 FROM sudoku_board_replay_history rh WHERE rh.sudoku_board_id = b.id AND rh.source = 'AUTOSOLVE') " +
           "ORDER BY b.cosmic_drip_level DESC, b.solve_time ASC", nativeQuery = true)
    @Cacheable(value = "autoSolvedCosmicGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAutoSolvedCosmicGames(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime < :cutoff AND b.isSolved = false AND b.hintCount <= :maxHints " +
           "ORDER BY b.startTime ASC")
    @Cacheable(value = "abandonedLowHintGames", key = "#cutoff + '-' + #maxHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAbandonedLowHintGames(@Param("cutoff") LocalDateTime cutoff, @Param("maxHints") int maxHints, Pageable pageable);

    @EntityGraph(attributePaths = {"replayHistory"})
    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.hintCount > 0 AND b.usedUndo = true " +
           "ORDER BY (b.hintCount + CASE WHEN b.usedUndo THEN 1 ELSE 0 END) DESC")
    @Cacheable(value = "assistedGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAssistedGames(@Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(b.solveTime.toSeconds()) as avgSolveTime, COUNT(b) as solvedCount, " +
           "AVG(b.cosmicDripLevel) as avgDrip FROM SudokuBoard b WHERE b.startTime > :since AND b.isSolved = true")
    Map<String, Number> getGameStatsSince(@Param("since") LocalDateTime since);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND SIZE(b.replayHistory) >= :minMoves " +
           "AND b.cosmicDripLevel >= :minDrip ORDER BY b.solveTime ASC")
    @Cacheable(value = "efficientCosmicMarathons", key = "#start + '-' + #end + '-' + #minMoves + '-' + #minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findEfficientCosmicMarathons(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                                   @Param("minMoves") int minMoves, @Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.timeLimitSeconds > 0 AND b.isSolved = false " +
           "AND (CURRENT_TIMESTAMP - b.startTime) > b.timeLimitSeconds ORDER BY b.startTime DESC")
    @Cacheable(value = "timedOutGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findTimedOutGames(@Param("since") LocalDateTime since, Pageable pageable);
}
