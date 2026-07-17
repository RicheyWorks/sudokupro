package com.xai.sudokupro.repository;

import com.xai.sudokupro.model.SudokuBoard;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
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
 *
 * NOTE: isSolved() is a computed method — the persisted column is `solved`.
 *       solveTime is @Transient — the persisted column is `solveTimeSeconds`.
 *       replayHistory / moveHistory are @Transient — use `moveCount` for size-based queries.
 *       startTime is LocalDateTime (mapped to TIMESTAMP).
 */
public interface GameRepository extends JpaRepository<SudokuBoard, Long> {

    @Query("SELECT b FROM SudokuBoard b WHERE b.chaosMode = true ORDER BY b.solveTimeSeconds DESC")
    @Cacheable(value = "chaosModeGames", key = "#pageable.pageNumber")
    List<SudokuBoard> findChaosModeGames(Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.mirrorMode = true AND b.hintCount <= :maxHints ORDER BY b.solveTimeSeconds ASC")
    @Cacheable(value = "fastMirrorGames", key = "#maxHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findFastMirrorGames(@Param("maxHints") int maxHints, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.gameId = :gameId")
    @Cacheable(value = "gameById", key = "#gameId")
    SudokuBoard findByGameId(@Param("gameId") String gameId);

    @Query("SELECT b FROM SudokuBoard b WHERE b.playerId = :playerId ORDER BY b.startTime DESC")
    List<SudokuBoard> findByPlayerId(@Param("playerId") String playerId, Pageable pageable);

    /**
     * Unfinished games a player can resume, newest first. Only rows with a
     * persisted cell snapshot qualify — rows written before the V3 migration
     * have no grid to restore. Deliberately NOT @Cacheable: the saved-games
     * list must reflect the latest saves immediately.
     */
    @Query("SELECT b FROM SudokuBoard b WHERE b.playerId = :playerId AND b.solved = false AND b.cellsJson IS NOT NULL ORDER BY b.startTime DESC")
    List<SudokuBoard> findResumableByPlayerId(@Param("playerId") String playerId, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.cosmicDripLevel >= :minDrip ORDER BY b.cosmicDripLevel DESC, b.solveTimeSeconds ASC")
    @Cacheable(value = "cosmicDripGames", key = "#minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findCosmicDripGames(@Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND b.solved = true ORDER BY b.solveTimeSeconds ASC")
    @Cacheable(value = "fastestSolves", key = "#start + '-' + #end + '-' + #pageable.pageNumber")
    List<SudokuBoard> findFastestSolvesInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.hintCount = 0 AND b.usedUndo = false AND b.solved = true ORDER BY b.solveTimeSeconds ASC")
    @Cacheable(value = "perfectClears", key = "#pageable.pageNumber")
    List<SudokuBoard> findPerfectClears(Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.moveCount >= :minMoves AND b.cosmicDripLevel > 0 ORDER BY b.moveCount DESC")
    @Cacheable(value = "longCosmicGames", key = "#minMoves + '-' + #pageable.pageNumber")
    List<SudokuBoard> findLongCosmicGames(@Param("minMoves") int minMoves, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT b.* FROM sudoku_boards b WHERE b.start_time > :since AND b.solved = false " +
           "ORDER BY CAST(b.time_limit_seconds AS FLOAT) - EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - b.start_time)) ASC", nativeQuery = true)
    @Cacheable(value = "activeUnfinishedGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findActiveUnfinishedGames(@Param("since") LocalDateTime since, Pageable pageable);

    /**
     * Cheap count variant — used by metrics schedulers that only need the number,
     * not the full rows.
     */
    @Transactional(readOnly = true)
    @Query(value = "SELECT COUNT(*) FROM sudoku_boards b WHERE b.start_time > :since AND b.solved = false",
           nativeQuery = true)
    long countActiveUnfinishedGames(@Param("since") LocalDateTime since);

    @Query("SELECT b FROM SudokuBoard b WHERE b.hintCount >= :minHints AND b.solved = false ORDER BY b.hintCount DESC")
    @Cacheable(value = "hintHeavyStrugglers", key = "#minHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findHintHeavyStrugglers(@Param("minHints") int minHints, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.chaosMode = true AND b.cosmicDripLevel >= :minDrip " +
           "ORDER BY b.cosmicDripLevel DESC, b.moveCount DESC")
    @Cacheable(value = "activeChaosDripMasters", key = "#since + '-' + #minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findActiveChaosDripMasters(@Param("since") LocalDateTime since, @Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND b.mirrorMode = true AND b.solved = true " +
           "ORDER BY (CASE WHEN b.moveCount > 0 THEN b.solveTimeSeconds / b.moveCount ELSE b.solveTimeSeconds END) ASC")
    @Cacheable(value = "efficientMirrorSolves", key = "#start + '-' + #end + '-' + #pageable.pageNumber")
    List<SudokuBoard> findEfficientMirrorSolvesInPeriod(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end, Pageable pageable);

    @Transactional(readOnly = true)
    @Query(value = "SELECT b.* FROM sudoku_boards b " +
           "WHERE b.start_time > :since AND b.solved = true " +
           "ORDER BY b.cosmic_drip_level DESC, b.solve_time_seconds ASC", nativeQuery = true)
    @Cacheable(value = "autoSolvedCosmicGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAutoSolvedCosmicGames(@Param("since") LocalDateTime since, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime < :cutoff AND b.solved = false AND b.hintCount <= :maxHints " +
           "ORDER BY b.startTime ASC")
    @Cacheable(value = "abandonedLowHintGames", key = "#cutoff + '-' + #maxHints + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAbandonedLowHintGames(@Param("cutoff") LocalDateTime cutoff, @Param("maxHints") int maxHints, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.hintCount > 0 AND b.usedUndo = true " +
           "ORDER BY (b.hintCount + CASE WHEN b.usedUndo = true THEN 1 ELSE 0 END) DESC")
    @Cacheable(value = "assistedGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findAssistedGames(@Param("since") LocalDateTime since, Pageable pageable);

    @Transactional(readOnly = true)
    @Query("SELECT AVG(b.solveTimeSeconds) as avgSolveTime, COUNT(b) as solvedCount, " +
           "AVG(b.cosmicDripLevel) as avgDrip FROM SudokuBoard b WHERE b.startTime > :since AND b.solved = true")
    Map<String, Number> getGameStatsSince(@Param("since") LocalDateTime since);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime BETWEEN :start AND :end AND b.moveCount >= :minMoves " +
           "AND b.cosmicDripLevel >= :minDrip ORDER BY b.solveTimeSeconds ASC")
    @Cacheable(value = "efficientCosmicMarathons", key = "#start + '-' + #end + '-' + #minMoves + '-' + #minDrip + '-' + #pageable.pageNumber")
    List<SudokuBoard> findEfficientCosmicMarathons(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end,
                                                   @Param("minMoves") int minMoves, @Param("minDrip") int minDrip, Pageable pageable);

    @Query("SELECT b FROM SudokuBoard b WHERE b.startTime > :since AND b.timeLimitSeconds > 0 AND b.solved = false " +
           "ORDER BY b.startTime DESC")
    @Cacheable(value = "timedOutGames", key = "#since + '-' + #pageable.pageNumber")
    List<SudokuBoard> findTimedOutGames(@Param("since") LocalDateTime since, Pageable pageable);
}
