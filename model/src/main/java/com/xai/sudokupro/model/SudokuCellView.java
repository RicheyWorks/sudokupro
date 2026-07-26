package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.time.Instant;

/**
 * An immutable view of a SudokuCell in SudokuPro's cosmic grid.
 *
 * <p>Every convenience accessor below is {@code @JsonIgnore}d. Jackson treats a public
 * no-arg getter as a serialized property, so this record was emitting not just its eleven
 * declared components but also {@code displayValue}, {@code timestampString},
 * {@code hintDisplay}, {@code empty}, {@code conflicted}, {@code hasPencilMarks} and — the
 * worst offender — the entire eight-key {@code toAnalyticsMap()} nested object, FOR EVERY
 * ONE OF THE 81 CELLS. A single {@code BoardState} weighed 25,729 bytes, more than 3x
 * Tomcat's default 8 KB WebSocket text buffer, and that payload is pushed to every session
 * in the game on every sync/undo/redo, on every replica. It also put server-side analytics
 * shape on the public wire. Keeping these helpers ignored is a wire-format contract: adding
 * a new public getter here silently re-inflates every board broadcast.
 */
@JsonPropertyOrder({"value", "isGiven", "lastModified", "moveSource", "pencilMarks", "conflicts", "hintStrength", "strategy", "isEditable", "playerId", "displayColor"})
public record SudokuCellView(
    @JsonProperty("value") int value,
    @JsonProperty("isGiven") boolean isGiven,
    @JsonProperty("lastModified") long lastModified,
    @JsonProperty("moveSource") SudokuCell.MoveSource moveSource,
    @JsonProperty("pencilMarks") Set<Integer> pencilMarks,
    @JsonProperty("conflicts") Set<Integer> conflicts,
    @JsonProperty("hintStrength") int hintStrength,
    @JsonProperty("strategy") SudokuCell.Strategy strategy,
    @JsonProperty("isEditable") boolean isEditable,
    @JsonProperty("playerId") String playerId,
    @JsonProperty("displayColor") String displayColor
) {

    private static final Logger logger = LoggerFactory.getLogger(SudokuCellView.class);

    public SudokuCellView(SudokuCell cell) {
        this(
            validateValue(cell.getValue()),
            cell.isGiven(),
            cell.getLastModified(),
            cell.getMoveSource(),
            safeSet(cell.getPencilMarks()),
            safeSet(cell.getConflicts()),
            cell.getHintStrength(),
            cell.getStrategy(),
            cell.isEditable(),
            extractPlayerId(cell),
            calculateDisplayColor(cell)
        );
    }

    // ---------------- SAFE HELPERS ----------------

    private static <T> Set<T> safeSet(Set<T> set) {
        return set == null ? Set.of() : set;
    }

    private static int validateValue(int value) {
        if (value < 0 || value > 9) {
            logger.warn("Invalid cell value {} detected; defaulting to 0", value);
            return 0;
        }
        return value;
    }

    private static String extractPlayerId(SudokuCell cell) {
        if (cell.getMoveSource() == SudokuCell.MoveSource.PLAYER && cell.getValue() != 0) {
            return "player_" + cell.getLastModified();
        }
        return null;
    }

    private static String calculateDisplayColor(SudokuCell cell) {
        if (cell.getConflicts() != null && !cell.getConflicts().isEmpty()) return "red";
        if (cell.getStrategy() == SudokuCell.Strategy.COSMIC) return "purple";
        if (cell.getStrategy() == SudokuCell.Strategy.STARFORGE) return "gold";
        if (cell.getHintStrength() > 0) return "green";
        if (cell.isGiven()) return "gray";
        return "black";
    }

    // ---------------- UTILS ----------------

    @JsonIgnore
    public boolean isEmpty() {
        return value == 0 && !isGiven;
    }

    @JsonIgnore
    public boolean isConflicted() {
        return conflicts != null && !conflicts.isEmpty();
    }

    @JsonIgnore
    public boolean hasPencilMarks() {
        return pencilMarks != null && !pencilMarks.isEmpty();
    }

    @JsonIgnore
    public String getDisplayValue() {
        if (value != 0) return String.valueOf(value);

        if (hasPencilMarks()) {
            return pencilMarks.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
        }

        return ".";
    }

    @JsonIgnore
    public String getTimestampString() {
        return Instant.ofEpochMilli(lastModified).toString();
    }

    @JsonIgnore
    public String getHintDisplay() {
        if (hintStrength == 0) return "";

        String strengthLabel = switch (hintStrength) {
            case 1 -> "Weak";
            case 2 -> "Medium";
            case 3 -> "Strong";
            default -> "Unknown";
        };

        return strategy != null ? strengthLabel + " (" + strategy + ")" : strengthLabel;
    }

    @JsonIgnore
    public Map<String, Object> toAnalyticsMap() {
        Map<String, Object> analytics = new HashMap<>();

        analytics.put("value", value);
        analytics.put("isGiven", isGiven);
        analytics.put("moveSource", moveSource != null ? moveSource.toString() : null);
        analytics.put("pencilMarksCount", pencilMarks != null ? pencilMarks.size() : 0);
        analytics.put("conflictCount", conflicts != null ? conflicts.size() : 0);
        analytics.put("hintStrength", hintStrength);
        analytics.put("strategy", strategy != null ? strategy.toString() : null);
        analytics.put("playerId", playerId);

        return analytics;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append(value == 0 ? "." : value);

        if (hasPencilMarks()) sb.append("[").append(pencilMarks).append("]");
        if (isConflicted()) sb.append("C").append(conflicts);
        if (hintStrength > 0) sb.append("H").append(hintStrength);
        if (strategy != null) sb.append("S:").append(strategy);
        if (playerId != null) sb.append("P:").append(playerId);
        if (!"black".equals(displayColor)) sb.append("[").append(displayColor).append("]");

        return sb.toString();
    }
}
