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
            // 1..5, matching GameService.validateDifficulty, SudokuBoard.generateBoard
            // and Constants. The controller capped at 4, so the platform's hardest
            // difficulty was unreachable through the API — and asking for it produced a
            // ConstraintViolationException that escaped as a 500 with no useful body,
            // rather than a 400 explaining the range. Found by the live engine.
            @RequestParam @Min(1) @Max(5) int difficulty,
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
            return ResponseEntity.ok(BoardState.from(
                gameService.getGameForReader(gameId, authService.getCurrentPlayerId())));
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(GAME_CREATION_ERROR, "Forbidden", e.getMessage()));
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
            // Owner-only: without this, one request could auto-solve any player's duel,
            // daily or tournament board — or a shared daily/weekly template, wrecking the
            // puzzle for everyone — and the response handed back the full solution.
            gameService.solveSudoku(gameId, authService.getCurrentPlayerId());
            return ResponseEntity.ok(BoardState.from(gameService.getGame(gameId)));
        } catch (SecurityException e) {
            return problemResponse(HttpStatus.FORBIDDEN, "Not Your Game", e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(GAME_CREATION_ERROR, "Unknown Game", e.getMessage()));
        }
    }

    @Operation(summary = "End (leave) a game; final state is persisted server-side")
    @PostMapping("/{gameId}/end")
    public ResponseEntity<Object> end(@PathVariable String gameId) {
        String playerId = authService.getCurrentPlayerId();
        try {
            // Spectators may watch but not end someone's game.
            SudokuBoard board = gameService.getGame(gameId);
            if (!playerId.equals(board.getPlayerId())) {
                return problemResponse(HttpStatus.FORBIDDEN, "Not Your Game",
                    "Game " + gameId + " belongs to " + board.getPlayerId());
            }
        } catch (IllegalArgumentException e) {
            return ResponseEntity.noContent().build(); // already gone — idempotent
        }
        gameService.endGame(gameId, playerId);
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
            return ResponseEntity.ok(Map.of("code",
                gameService.exportShareCode(gameId, authService.getCurrentPlayerId())));
        } catch (SecurityException e) {
            return problemResponse(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
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

    /**
     * Date after which the deprecated {@code GET /api/game/hint} may be removed (RFC 8594).
     * Advertised on every GET response so a client operator can see the deadline without
     * reading our source.
     */
    private static final String HINT_GET_SUNSET = "Sat, 31 Oct 2026 23:59:59 GMT";

    @Operation(summary = "Buy a hint from the AI solver (spends gems)")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hint returned"),
        @ApiResponse(responseCode = "402", description = "Not enough gems",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "403", description = "Board belongs to another player",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json")),
        @ApiResponse(responseCode = "500", description = "Error retrieving hint",
                content = @io.swagger.v3.oas.annotations.media.Content(mediaType = "application/problem+json"))
    })
    @PostMapping("/hint")
    public ResponseEntity<Object> buyHint(@RequestParam(required = false) String gameId) {
        return hintResponse(gameId, false);
    }

    /**
     * Deprecated: use {@code POST /api/game/hint}.
     *
     * <p>This endpoint charged gems, incremented {@code hintCount} and wrote the board — on a
     * GET. HTTP defines GET as safe (RFC 9110 §9.2.1), and the ecosystem acts on that
     * literally: a browser reload, a bfcache restore, a {@code <link rel=prefetch>}, a
     * security proxy's URL warm-up, or a crawler that finds the URL in a log will all replay
     * it with no user involvement, and each replay spent five of the player's gems and cost
     * them the clean-solve bonus by inflating a hint count they never asked for.
     *
     * <p>Rather than break the already-shipped JavaFX desktop client ({@code client/}) that
     * calls this URL, the route stays — but it now delegates to
     * {@link com.xai.sudokupro.service.GameService#getHintIdempotent(String, String)}, so
     * repeating it against an unchanged grid replays the hint already issued, free. It is
     * therefore safe and idempotent as HTTP requires, while still answering old clients. The
     * response advertises its own deprecation so the migration is visible in-band rather
     * than only in a changelog.
     */
    @Deprecated
    @Operation(summary = "Deprecated — use POST /api/game/hint. Safe/idempotent hint read.",
               deprecated = true)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Hint returned (replayed if unchanged)")
    })
    @GetMapping("/hint")
    public ResponseEntity<Object> getHint(
            @RequestParam(required = false) String gameId) {
        return hintResponse(gameId, true);
    }

    /**
     * @param idempotent {@code true} for the deprecated GET (replay an already-issued hint
     *                   for an unchanged grid), {@code false} for the POST (always a purchase)
     */
    private ResponseEntity<Object> hintResponse(String gameId, boolean idempotent) {
        try {
            String hint;
            String playerId = authService.getCurrentPlayerId();
            if (gameId != null && !gameId.isBlank()) {
                // Pass the caller so the charge lands on them and a non-owner is refused;
                // without this a spectator could drain the board owner's wallet.
                hint = idempotent
                    ? gameService.getHintIdempotent(gameId, playerId)
                    : gameService.getHint(gameId, playerId);
            } else {
                hint = idempotent
                    ? gameService.getHintForPlayerIdempotent(playerId)
                    : gameService.getHintForPlayer(playerId);
            }
            if (hint == null || hint.isEmpty()) {
                logger.debug("No hint available for current game state");
                return hintHeaders(ResponseEntity.ok(), idempotent)
                    .body(Map.of("hint", "No hint available—keep solving!"));
            }
            logger.debug("Hint provided: {}", hint);
            return hintHeaders(ResponseEntity.ok(), idempotent).body(Map.of("hint", hint));
        } catch (com.xai.sudokupro.service.economy.InsufficientGemsException e) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(buildProblem(HINT_FAILURE_ERROR, "Not Enough Gems",
                    "Hints cost " + e.cost() + " gems; you have " + e.balance()
                    + ". Solve puzzles to earn more."));
        } catch (SecurityException e) {
            // Asking for a hint on somebody else's board (would have charged them).
            return problemResponse(HttpStatus.FORBIDDEN, "Not Your Game", e.getMessage());
        } catch (IllegalArgumentException e) {
            // getGame throws this for an unknown id. It used to fall through to the
            // generic handler below and return 500 with the raw internal message, while
            // every sibling endpoint (/{id}, /solve, /save, /resume) mapped it to 404.
            return problemResponse(HttpStatus.NOT_FOUND, "Unknown Game", e.getMessage());
        } catch (IllegalStateException e) {
            // GameLockManager: the game is held on another replica. 409, not 500.
            return problemResponse(HttpStatus.CONFLICT, "Game Busy", e.getMessage());
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

    /**
     * Headers common to both hint verbs.
     *
     * <p>{@code Cache-Control: no-store} on both: a hint is per-player, per-board and paid
     * for, so no intermediary may keep a copy — and a cached GET response is one more way the
     * old endpoint could be replayed. The deprecation signalling (RFC 8594 {@code Deprecation}
     * and {@code Sunset}, plus a {@code Link} to the successor) rides on the GET only.
     */
    private ResponseEntity.BodyBuilder hintHeaders(ResponseEntity.BodyBuilder builder, boolean deprecated) {
        builder.header("Cache-Control", "no-store");
        if (deprecated) {
            builder.header("Deprecation", "true");
            builder.header("Sunset", HINT_GET_SUNSET);
            builder.header("Link", "</api/game/hint>; rel=\"successor-version\"; method=\"POST\"");
        }
        return builder;
    }

    /**
     * Parameter-validation failures are the caller's fault, not the server's.
     *
     * <p>{@code @Min}/{@code @Max} on a controller parameter raise
     * {@code ConstraintViolationException}, which has no default Spring MVC mapping — so
     * every out-of-range parameter surfaced as a 500 with an empty problem body and a
     * stack trace in the log. A client cannot tell "you asked for something impossible"
     * from "the server is broken", and a 500 is what monitoring pages on.
     */
    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            jakarta.validation.ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
            .map(v -> v.getPropertyPath() + " " + v.getMessage())
            .reduce((a2, b2) -> a2 + "; " + b2)
            .orElse("invalid request parameter");
        return problemResponse(HttpStatus.BAD_REQUEST, "Invalid Parameter", detail);
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
