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

    @Autowired
    public SudokuGameController(GameService gameService, AuthService authService) {
        this.gameService  = gameService;
        this.authService  = authService;
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

    // The old /save stub was removed (AUDIT P1-3): it claimed success while
    // persisting nothing. Lifecycle is now: POST /new, GET /{id}, POST /{id}/solve,
    // POST /{id}/end; moves run over the WebSocket channel.
}
