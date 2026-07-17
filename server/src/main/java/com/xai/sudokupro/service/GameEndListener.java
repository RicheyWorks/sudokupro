package com.xai.sudokupro.service;

import com.xai.sudokupro.model.SudokuBoard;

/**
 * Observes finished games (solved or abandoned) as they pass through
 * {@link GameService#endGame} — the single point every completed game crosses.
 * Implementations (daily puzzle, duels) are resolved lazily via ObjectProvider
 * so they may themselves depend on GameService without a constructor cycle.
 */
public interface GameEndListener {

    void onGameEnded(SudokuBoard board, String playerId);
}
