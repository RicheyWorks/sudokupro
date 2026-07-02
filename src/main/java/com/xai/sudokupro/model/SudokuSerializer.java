package com.xai.sudokupro.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.xai.sudokupro.service.AISolverService;
import com.xai.sudokupro.util.Constants;
import java.io.IOException;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
 * The cosmic scribe of SudokuPro.
 * Serializes and deserializes SudokuBoard states with divine precision—packing grids, moves, pencil marks, and cosmic flair for epic duels.
 */
@Component
public class SudokuSerializer {
    private static final Logger logger = LoggerFactory.getLogger(SudokuSerializer.class);
    private final ObjectMapper mapper;
    private final AISolverService solver; // For board reconstruction

    @Autowired
    public SudokuSerializer(ObjectMapper mapper, AISolverService solver) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper cannot be null");
        this.solver = Objects.requireNonNull(solver, "AISolverService cannot be null");
        logger.info("SudokuSerializer initialized with ObjectMapper and AISolverService");
    }

    public String serialize(SudokuBoard board) {
        try {
            BoardData data = new BoardData();
            data.cells = new int[Constants.BOARD_SIZE][Constants.BOARD_SIZE];
            data.pencilMarks = new HashMap<>();
            data.conflicts = new HashMap<>();
            for (int i = 0; i < Constants.BOARD_SIZE; i++) {
                for (int j = 0; j < Constants.BOARD_SIZE; j++) {
                    SudokuCell cell = board.getCell(i, j);
                    data.cells[i][j] = cell.getValue();
                    if (!cell.getPencilMarks().isEmpty()) data.pencilMarks.put(i + "," + j, cell.getPencilMarks());
                    if (!cell.getConflicts().isEmpty()) data.conflicts.put(i + "," + j, cell.getConflicts());
                }
            }
            data.chaosMode = board.isChaosMode();
            data.mirrorMode = board.isMirrorMode();
            data.timeLimitSeconds = board.getTimeLimitSeconds();
            data.gameId = board.getGameId();
            data.replayHistory = board.getReplayHistory();
            data.hintCount = board.getHintCount();
            data.usedUndo = board.isUsedUndo();
            data.solveTimeSeconds = board.getSolveTime().toSeconds();
            data.cosmicSignatures = extractCosmicSignatures(board);
            data.cosmicDripLevel = calculateCosmicDripLevel(board);
            data.version = "1.0"; // Add versioning for future compatibility

            String json = mapper.writeValueAsString(data);
            String compressedJson = compress(json);
            logger.debug("Serialized board {}: {} bytes (compressed)", data.gameId, compressedJson.length());
            return compressedJson;
        } catch (IOException e) {
            logger.error("Failed to serialize SudokuBoard {}: {}", board.getGameId(), e.getMessage());
            throw new RuntimeException("Serialization failed", e);
        }
    }

    public SudokuBoard deserialize(String json) {
        try {
            String decompressedJson = decompress(json);
            BoardData data = mapper.readValue(decompressedJson, BoardData.class);
            validateBoardData(data);

            SudokuCell[][] cells = new SudokuCell[Constants.BOARD_SIZE][Constants.BOARD_SIZE];
            for (int i = 0; i < Constants.BOARD_SIZE; i++) {
                for (int j = 0; j < Constants.BOARD_SIZE; j++) {
                    cells[i][j] = new SudokuCell();
                    cells[i][j].setValue(data.cells[i][j], SudokuCell.MoveSource.INITIAL, null);
                    cells[i][j].setGiven(data.cells[i][j] != 0);
                    Set<Integer> pencilMarks = data.pencilMarks.get(i + "," + j);
                    if (pencilMarks != null) pencilMarks.forEach(cells[i][j]::addPencilMark);
                    Set<Integer> conflicts = data.conflicts.get(i + "," + j);
                    if (conflicts != null) conflicts.forEach(cells[i][j]::addConflict);
                    SudokuGenerator.CosmicSignature sig = data.cosmicSignatures.get(i + "," + j);
                    if (sig != null) {
                        cells[i][j].setValue(sig.getValue(), SudokuCell.MoveSource.AUTOSOLVE, sig.getStrategy());
                    }
                }
            }

            SudokuBoard board = new SudokuBoard(cells, data.chaosMode, data.mirrorMode, data.timeLimitSeconds, data.gameId);
            if (data.replayHistory != null) {
                board.applyBatchMoves(data.replayHistory); // Batch apply for efficiency
            }
            // hintCount, usedUndo, solveTimeSeconds, and cosmicDripLevel are read-only
            // computed fields on SudokuBoard — no setters exist; they are re-derived from
            // the cell state and move history that was just applied above.

            logger.debug("Deserialized board {} with version {}", data.gameId, data.version);
            return board;
        } catch (IOException e) {
            logger.error("Failed to deserialize SudokuBoard: {}", e.getMessage());
            throw new RuntimeException("Deserialization failed", e);
        }
    }

    private Map<String, SudokuGenerator.CosmicSignature> extractCosmicSignatures(SudokuBoard board) {
        Map<String, SudokuGenerator.CosmicSignature> signatures = new HashMap<>();
        for (int i = 0; i < Constants.BOARD_SIZE; i++) {
            for (int j = 0; j < Constants.BOARD_SIZE; j++) {
                SudokuCell cell = board.getCell(i, j);
                if (cell.getStrategy() == SudokuCell.Strategy.COSMIC || cell.getStrategy() == SudokuCell.Strategy.STARFORGE) {
                    signatures.put(i + "," + j, new SudokuGenerator.CosmicSignature(cell.getValue(), cell.getStrategy()));
                }
            }
        }
        return signatures;
    }

    private int calculateCosmicDripLevel(SudokuBoard board) {
        int dripCount = 0;
        for (int i = 0; i < Constants.BOARD_SIZE; i++) {
            for (int j = 0; j < Constants.BOARD_SIZE; j++) {
                SudokuCell cell = board.getCell(i, j);
                if (cell.getStrategy() == SudokuCell.Strategy.COSMIC || 
                    cell.getStrategy() == SudokuCell.Strategy.STARFORGE) {
                    dripCount++;
                }
            }
        }
        return dripCount;
    }

    private void validateBoardData(BoardData data) {
        if (data.cells == null || data.cells.length != Constants.BOARD_SIZE || 
            Arrays.stream(data.cells).anyMatch(row -> row.length != Constants.BOARD_SIZE)) {
            logger.error("Invalid board dimensions in deserialized data");
            throw new IllegalArgumentException("Board dimensions must be " + Constants.BOARD_SIZE + "x" + Constants.BOARD_SIZE);
        }
        if (data.timeLimitSeconds < 0) {
            logger.warn("Negative time limit {} detected; defaulting to 0", data.timeLimitSeconds);
            data.timeLimitSeconds = 0;
        }
        if (data.version == null) {
            logger.warn("No version specified in deserialized data; assuming 1.0");
            data.version = "1.0";
        }
    }

    private String compress(String data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(baos)) {
            gzip.write(data.getBytes("UTF-8"));
        }
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private String decompress(String compressed) throws IOException {
        byte[] bytes = Base64.getDecoder().decode(compressed);
        try (ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
             GZIPInputStream gzip = new GZIPInputStream(bais)) {
            return new String(gzip.readAllBytes(), "UTF-8");
        }
    }

    private static class BoardData {
        public int[][] cells;
        public Map<String, Set<Integer>> pencilMarks;
        public Map<String, Set<Integer>> conflicts;
        public boolean chaosMode;
        public boolean mirrorMode;
        public long timeLimitSeconds;
        public String gameId;
        @JsonSerialize(contentUsing = ToStringSerializer.class)
        @JsonDeserialize(contentAs = EnhancedMove.class)
        public List<EnhancedMove> replayHistory;
        public int hintCount;
        public boolean usedUndo;
        public long solveTimeSeconds;
        public Map<String, SudokuGenerator.CosmicSignature> cosmicSignatures;
        public int cosmicDripLevel;
        public String version; // Added for versioning

        public BoardData() {
            this.cells = new int[Constants.BOARD_SIZE][Constants.BOARD_SIZE];
            this.pencilMarks = new HashMap<>();
            this.conflicts = new HashMap<>();
            this.replayHistory = new ArrayList<>();
            this.cosmicSignatures = new HashMap<>();
            this.version = "1.0";
        }
    }
}
