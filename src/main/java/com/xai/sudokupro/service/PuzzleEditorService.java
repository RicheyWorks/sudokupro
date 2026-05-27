package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.repository.GameRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

@Service
public class PuzzleEditorService {
    private static final Logger logger = LoggerFactory.getLogger(PuzzleEditorService.class);
    private static final int GRID_SIZE = 9;
    private static final Tags GLOBAL_TAGS = Tags.of("app", "SudokuPro");

    private final AISolverService aiSolverService;
    private final GameRepository gameRepository;
    private final MeterRegistry meterRegistry;

    @Autowired
    public PuzzleEditorService(AISolverService aiSolverService, GameRepository gameRepository,
                               MeterRegistry meterRegistry) {
        this.aiSolverService = Objects.requireNonNull(aiSolverService);
        this.gameRepository = Objects.requireNonNull(gameRepository);
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
    }

    public SudokuBoard createCustomPuzzle(int[][] customGrid, String playerId, boolean isAdminVerified) {
        validateGrid(customGrid);
        validatePlayerId(playerId);

        String gameId = UUID.randomUUID().toString();

        try {
            SudokuBoard board = new SudokuBoard(0, false, false, 0L, gameId);

            SudokuCell[][] cells = board.getBoard();

            for (int i = 0; i < GRID_SIZE; i++) {
                for (int j = 0; j < GRID_SIZE; j++) {
                    int value = customGrid[i][j];

                    if (value >= 0 && value <= 9) {
                        cells[i][j].setValue(value, SudokuCell.MoveSource.PLAYER);

                        if (value != 0) {
                            cells[i][j].setGiven(true);
                        }
                    } else {
                        throw new IllegalArgumentException("Grid value must be 0-9");
                    }
                }
            }

            validateBoard(board);

            gameRepository.save(board);

            int difficulty = estimateDifficulty(board);

            meterRegistry.counter("sudokupro.custom.puzzles.created", GLOBAL_TAGS).increment();

            meterRegistry.counter(
                "sudokupro.custom.puzzles.by.difficulty",
                Tags.concat(GLOBAL_TAGS, Tags.of("level", String.valueOf(difficulty)))
            ).increment();

            return board;

        } catch (Exception e) {
            meterRegistry.counter("sudokupro.custom.puzzles.failed", GLOBAL_TAGS).increment();
            throw new RuntimeException("Custom puzzle creation failed", e);
        }
    }

    public int estimateDifficulty(SudokuBoard board) {
        validateBoard(board);

        int emptyCells = (int) Arrays.stream(board.getBoardCopy())
            .flatMap(Arrays::stream)
            .filter(cell -> cell.getValue() == 0)
            .count();

        int difficulty = (81 - emptyCells) / 9;
        return Math.min(10, Math.max(0, difficulty));
    }

    private void validateGrid(int[][] grid) {
        if (grid == null || grid.length != GRID_SIZE) {
            throw new IllegalArgumentException("Invalid grid");
        }

        for (int[] row : grid) {
            if (row == null || row.length != GRID_SIZE) {
                throw new IllegalArgumentException("Invalid row");
            }
        }
    }

    private void validateBoard(SudokuBoard board) {
        for (int i = 0; i < GRID_SIZE; i++) {
            for (int j = 0; j < GRID_SIZE; j++) {
                if (board.getCell(i, j).isConflicted()) {
                    throw new IllegalArgumentException("Conflicts in grid");
                }
            }
        }

        if (!aiSolverService.solveSudoku(board)) {
            throw new IllegalArgumentException("Not solvable");
        }
    }

    private void validatePlayerId(String playerId) {
        if (playerId == null || playerId.isBlank()) {
            throw new IllegalArgumentException("Invalid playerId");
        }
    }
}
