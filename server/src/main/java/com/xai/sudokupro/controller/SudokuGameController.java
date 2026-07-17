package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.SudokuBoard;
import com.xai.sudokupro.model.api.BoardState;
import com.xai.sudokupro.service.AuthService;
import com.xai.sudokupro.service.GameService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.*;
import java.util.Map;

/**
 * REST controller for SudokuPro's game logic.
 *
 * <p>Board-returning endpoints serialize {@link BoardState} — a player-visible
 * projection — never the raw {@link SudokuBoard} entity. Moves flow over the
 * WebSocket channel ({@code /ws/game}); REST covers game lifecycle and queries.
 */
@RestController
@RequestMapping("/api/game")
@Validated
@Tag(name = "Game API", description = "Endpoints for Sudoku gameplay")
public class SudokuGameController {

    private static final Logger logger = LoggerFactory.getLogger(SudokuGameController.class);

    private static final String GAME_CREATION_ERROR = "https://sudokupro.com/errors/game-creation-failed";
    private static final String HINT_FAILURE_ERROR = "https://sudokupro.com/errors/hint-failure";

    private final GameService gameService;
    private final AuthService authService;
    private final com.xai.sudokupro.service.SmartDifficultyService smartDifficulty;

    @Autowired
    public SudokuGameController(GameService gameService, AuthService authService,
                                com.xai.sudokupro.service.SmartDifficultyService smartDifficulty) {
        this.gameService  = gameService;
        this.authService  = authService;
        this.smartDifficulty = smartDifficulty;
    }

    @Operation(summary = "Create a new Sudoku game with specified difficulty")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Game created successfully"),
        @ApiResponse(responseCode = "400", description = "Invalid difficulty",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "500", description = "Error creating game",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json"))
    })
    @PostMapping("/new")
    public ResponseEntity<Object> createGame(
            @RequestParam @Min(1) @Max(4) int difficulty,
            @RequestParam(defaultValue = "false") boolean chaos,
            @RequestParam(defaultValue = "false") boolean mirror) {
        try {
            // Use authenticated player ID; falls back to "anonymous" for unauthenticated callers.
            String playerId = authService.getCurrentPlayerId();
            SudokuBoard board = gameService.createNewGame(difficulty, playerId, chaos, mirror);
            logger.info("New game created: difficulty={} player={}", difficulty, playerId);
            return ResponseEntity.ok(BoardState.from(board));
        } catch (Exception e) {
            logger.error("Failed to create new game: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(
                    GAME_CREATION_ERROR,
                    "Game Creation Failed",
                    "An error occurred while generating the game: " + e.getMessage()
                ));
        }
    }

    @Operation(summary = "Get the current state of a game")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Game state returned"),
        @ApiResponse(responseCode = "404", description = "Unknown game")
    })
    @GetMapping("/{gameId}")
    public ResponseEntity<Object> getGame(@PathVariable String gameId) {
        try {
            return ResponseEntity.ok(BoardState.from(gameService.getGame(gameId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(GAME_CREATION_ERROR, "Unknown Game", e.getMessage()));
        }
    }

    @Operation(summary = "Auto-solve the board with the AI solver")
    @PostMapping("/{gameId}/solve")
    public ResponseEntity<Object> solve(@PathVariable String gameId) {
        try {
            gameService.solveSudoku(gameId);
            return ResponseEntity.ok(BoardState.from(gameService.getGame(gameId)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(GAME_CREATION_ERROR, "Unknown Game", e.getMessage()));
        }
    }

    @Operation(summary = "End (leave) a game; final state is persisted server-side")
    @PostMapping("/{gameId}/end")
    public ResponseEntity<Void> end(@PathVariable String gameId) {
        gameService.endGame(gameId, authService.getCurrentPlayerId());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Explicitly save a game so it can be resumed later")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Game persisted"),
        @ApiResponse(responseCode = "403", description = "Game belongs to another player",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "404", description = "Unknown game",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json"))
    })
    @PostMapping("/{gameId}/save")
    public ResponseEntity<Object> save(@PathVariable String gameId) {
        try {
            SudokuBoard board = gameService.saveGame(gameId, authService.getCurrentPlayerId());
            return ResponseEntity.ok(Map.of("status", "saved", "gameId", board.getGameId()));
        } catch (SecurityException e) {
            return problemResponse(HttpStatus.FORBIDDEN, "Not Your Game", e.getMessage());
        } catch (IllegalArgumentException e) {
            return problemResponse(HttpStatus.NOT_FOUND, "Unknown Game", e.getMessage());
        }
    }

    @Operation(summary = "List the caller's saved (unfinished, resumable) games, newest first")
    @GetMapping("/saved")
    public ResponseEntity<Object> savedGames(@RequestParam(defaultValue = "10") @Min(1) @Max(50) int limit) {
        String playerId = authService.getCurrentPlayerId();
        return ResponseEntity.ok(gameService.listSavedGames(playerId, limit).stream()
            .map(BoardState::from)
            .toList());
    }

    @Operation(summary = "Resume a previously saved game")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Game state returned, game re-activated"),
        @ApiResponse(responseCode = "403", description = "Game belongs to another player",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "404", description = "Unknown game",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "409", description = "Game already finished",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json"))
    })
    @PostMapping("/{gameId}/resume")
    public ResponseEntity<Object> resume(@PathVariable String gameId) {
        try {
            SudokuBoard board = gameService.resumeGame(gameId, authService.getCurrentPlayerId());
            return ResponseEntity.ok(BoardState.from(board));
        } catch (SecurityException e) {
            return problemResponse(HttpStatus.FORBIDDEN, "Not Your Game", e.getMessage());
        } catch (IllegalStateException e) {
            return problemResponse(HttpStatus.CONFLICT, "Game Already Finished", e.getMessage());
        } catch (IllegalArgumentException e) {
            return problemResponse(HttpStatus.NOT_FOUND, "Unknown Game", e.getMessage());
        }
    }

    @Operation(summary = "The difficulty the adaptive model recommends for the caller")
    @GetMapping("/recommended-difficulty")
    public ResponseEntity<Object> recommendedDifficulty() {
        return ResponseEntity.ok(Map.of("difficulty",
            smartDifficulty.recommendedDifficulty(authService.getCurrentPlayerId())));
    }

    @Operation(summary = "The player's current active game id (for spectating). Per-pod lookup.")
    @GetMapping("/active-of/{playerId}")
    public ResponseEntity<Object> activeGameOf(@PathVariable String playerId) {
        String gameId = gameService.findActiveGameForPlayer(playerId);
        if (gameId == null) {
            return problemResponse(HttpStatus.NOT_FOUND, "No Active Game",
                playerId + " is not playing right now");
        }
        return ResponseEntity.ok(Map.of("gameId", gameId));
    }

    @Operation(summary = "Share code for a puzzle (gzipped grid, never the solution)")
    @GetMapping("/{gameId}/share")
    public ResponseEntity<Object> share(@PathVariable String gameId) {
        try {
            return ResponseEntity.ok(Map.of("code", gameService.exportShareCode(gameId)));
        } catch (IllegalArgumentException e) {
            return problemResponse(HttpStatus.NOT_FOUND, "Unknown Game", e.getMessage());
        }
    }

    public record ImportRequest(@jakarta.validation.constraints.NotBlank
                                @jakarta.validation.constraints.Size(max = 16384) String code) {}

    @Operation(summary = "Import a shared puzzle as a fresh game of your own")
    @PostMapping("/import")
    public ResponseEntity<Object> importShared(@RequestBody @Validated ImportRequest request) {
        try {
            SudokuBoard board = gameService.importShareCode(request.code(), authService.getCurrentPlayerId());
            return ResponseEntity.ok(BoardState.from(board));
        } catch (IllegalArgumentException e) {
            return problemResponse(HttpStatus.BAD_REQUEST, "Bad Share Code", e.getMessage());
        }
    }

    @Operation(summary = "Get a hint from the AI solver")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hint returned"),
        @ApiResponse(responseCode = "500", description = "Error retrieving hint",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json"))
    })
    @GetMapping("/hint")
    public ResponseEntity<Object> getHint(
            @RequestParam(required = false) String gameId) {
        try {
            String hint;
            if (gameId != null && !gameId.isBlank()) {
                hint = gameService.getHint(gameId);
            } else {
                String playerId = authService.getCurrentPlayerId();
                hint = gameService.getHintForPlayer(playerId);
            }
            if (hint == null || hint.isEmpty()) {
                logger.debug("No hint available for current game state");
                return ResponseEntity.ok(Map.of("hint", "No hint available—keep solving!"));
            }
            logger.debug("Hint provided: {}", hint);
            return ResponseEntity.ok(Map.of("hint", hint));
        } catch (com.xai.sudokupro.service.economy.InsufficientGemsException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HINT_FAILURE_ERROR, "Not Enough Gems",
                    "Hints cost " + e.cost() + " gems; you have " + e.balance()
                    + ". Solve puzzles to earn more."));
        } catch (Exception e) {
            logger.error("Failed to retrieve hint: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(
                    HINT_FAILURE_ERROR,
                    "Hint Retrieval Failed",
                    "Unable to fetch hint: " + e.getMessage()
                ));
        }
    }

    private Map<String, String> buildProblem(String type, String title, String detail) {
        return Map.of("type", type, "title", title, "detail", detail);
    }

    private ResponseEntity<Object> problemResponse(HttpStatus status, String title, String detail) {
        return ResponseEntity.status(status)
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .body(buildProblem(GAME_CREATION_ERROR, title, detail));
    }

    // The old /save stub was removed (AUDIT P1-3): it claimed success while
    // persisting nothing. POST /{id}/save above is the real implementation —
    // it persists the full grid (cells_json) and is paired with GET /saved and
    // POST /{id}/resume. Moves still run over the WebSocket channel.
}
