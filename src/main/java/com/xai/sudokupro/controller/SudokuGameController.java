package com.xai.sudokupro.controller;

import com.xai.sudokupro.model.SudokuBoard;
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
            @RequestParam @Min(1) @Max(4) int difficulty) {
        try {
            // Use authenticated player ID; falls back to "anonymous" for unauthenticated callers.
            String playerId = authService.getCurrentPlayerId();
            SudokuBoard board = gameService.createNewGame(difficulty, playerId, false, false);
            logger.info("New game created: difficulty={} player={}", difficulty, playerId);
            return ResponseEntity.ok(board);
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

    // Future expansion stubs (UNCHANGED)
    /*
    @PostMapping("/save")
    public ResponseEntity<Object> saveGame(@RequestBody SudokuBoard board) {
        logger.info("Game save requested");
        return ResponseEntity.ok(Map.of("status", "saved", "gameId", "TBD"));
    }

    @GetMapping("/load/{id}")
    public ResponseEntity<Object> loadGame(@PathVariable String id) {
        logger.info("Game load requested for ID: {}", id);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(null);
    }

    @PostMapping("/move")
    public ResponseEntity<Object> submitMove(@RequestBody Map<String, Object> move) {
        logger.info("Move submitted: {}", move);
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(null);
    }

    @GetMapping("/validate")
    public ResponseEntity<Object> validateBoard() {
        logger.info("Board validation requested");
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(null);
    }
    */
}

