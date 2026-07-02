package com.xai.sudokupro.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

@JsonPropertyOrder({"value","pencilMarks","isGiven","lastModified","moveSource","conflicts","hintStrength","strategy"})
public class SudokuCell implements Cloneable, Serializable {
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(SudokuCell.class);

    @JsonProperty("value")      private volatile int value;
    @JsonProperty("pencilMarks") private final Set<Integer> pencilMarks;
    @JsonProperty("isGiven")    private volatile boolean isGiven;
    @JsonProperty("lastModified") private volatile long lastModified;
    @JsonProperty("moveSource") private volatile MoveSource moveSource;
    @JsonProperty("conflicts")  private final Set<Integer> conflicts;
    @JsonProperty("hintStrength") private volatile int hintStrength;
    @JsonProperty("strategy")   private volatile Strategy strategy;

    public SudokuCell() {
        this.value = 0;
        this.pencilMarks = Collections.synchronizedSet(new HashSet<>());
        this.isGiven = false;
        this.lastModified = Instant.now().toEpochMilli();
        this.moveSource = MoveSource.INITIAL;
        this.conflicts = Collections.synchronizedSet(new HashSet<>());
        this.hintStrength = 0;
        this.strategy = null;
    }

    // ---- setValue overloads ----
    public synchronized void setValue(int value) {
        setValue(value, MoveSource.UNKNOWN, null);
    }

    public synchronized void setValue(int value, MoveSource source) {
        setValue(value, source, null);
    }

    public synchronized void setValue(int value, MoveSource source, Strategy strategy) {
        if (value < 0 || value > 9) throw new IllegalArgumentException("Value must be 0-9");
        if (isGiven && value != this.value) {
            logger.warn("Attempted to modify given cell from {} to {}", this.value, value);
            return;
        }
        this.value = value;
        this.lastModified = Instant.now().toEpochMilli();
        this.moveSource = source != null ? source : MoveSource.UNKNOWN;
        this.strategy = strategy;
        if (value != 0) {
            this.pencilMarks.clear();
            this.hintStrength = 0;
        } else {
            this.isGiven = false;
        }
        conflicts.clear(); // caller responsible for re-validating
    }

    // ---- Pencil marks ----
    public Set<Integer> getPencilMarks() {
        synchronized (pencilMarks) { return new HashSet<>(pencilMarks); }
    }

    public synchronized void addPencilMark(int mark) {
        if (mark < 1 || mark > 9) throw new IllegalArgumentException("Pencil mark must be 1-9");
        if (value == 0 && !isGiven) pencilMarks.add(mark);
    }

    public synchronized void removePencilMark(int mark) {
        pencilMarks.remove(mark);
    }

    public synchronized void retainPencilMarks(Set<Integer> marks) {
        if (value == 0 && !isGiven) pencilMarks.retainAll(marks);
    }

    public synchronized void togglePencilMark(int mark) {
        if (mark < 1 || mark > 9) throw new IllegalArgumentException("Pencil mark must be 1-9");
        if (value == 0 && !isGiven) {
            if (!pencilMarks.add(mark)) pencilMarks.remove(mark);
        }
    }

    public synchronized boolean hasPencilMark(int mark) { return pencilMarks.contains(mark); }
    public synchronized void clearPencilMarks() { pencilMarks.clear(); }

    // ---- Conflicts ----
    public synchronized void addConflict(int v) { if (v >= 1 && v <= 9) conflicts.add(v); }
    public synchronized Set<Integer> getConflicts() { return new HashSet<>(conflicts); }
    public synchronized void clearConflicts() { conflicts.clear(); }
    public synchronized boolean isConflicted() { return !conflicts.isEmpty(); }
    public synchronized void updateConflicts() { conflicts.clear(); }

    // ---- Getters/setters ----
    public synchronized int getValue() { return value; }
    public synchronized boolean isGiven() { return isGiven; }
    public synchronized void setGiven(boolean given) { this.isGiven = given; }
    public synchronized long getLastModified() { return lastModified; }
    public synchronized MoveSource getMoveSource() { return moveSource; }
    public synchronized int getHintStrength() { return hintStrength; }
    public synchronized void setHintStrength(int s) {
        if (s < 0 || s > 3) throw new IllegalArgumentException("Hint strength must be 0-3");
        this.hintStrength = s;
    }
    public synchronized Strategy getStrategy() { return strategy; }
    public synchronized void setStrategy(Strategy strategy) { this.strategy = strategy; }
    public synchronized boolean isEditable() { return !isGiven && value == 0; }

    public synchronized void applyHint(Hint hint) {
        if (hint == null) return;
        if (hint.value() instanceof Integer val) {
            setValue(val, MoveSource.HINT, hint.strategy());
            setHintStrength(3);
        } else if (hint.value() instanceof List<?> list) {
            @SuppressWarnings("unchecked")
            List<Integer> intList = (List<Integer>) list;
            retainPencilMarks(new HashSet<>(intList));
            setHintStrength(intList.size() == 1 ? 3 : intList.size() <= 3 ? 2 : 1);
            this.strategy = hint.strategy();
        }
    }

    public synchronized void reset() {
        value = 0; pencilMarks.clear(); isGiven = false;
        lastModified = Instant.now().toEpochMilli();
        moveSource = MoveSource.INITIAL; conflicts.clear(); hintStrength = 0; strategy = null;
    }

    @Override
    public synchronized SudokuCell clone() {
        SudokuCell copy = new SudokuCell();
        copy.value = this.value;
        synchronized (pencilMarks) { copy.pencilMarks.addAll(this.pencilMarks); }
        synchronized (conflicts)  { copy.conflicts.addAll(this.conflicts); }
        copy.isGiven = this.isGiven;
        copy.lastModified = this.lastModified;
        copy.moveSource = this.moveSource;
        copy.hintStrength = this.hintStrength;
        copy.strategy = this.strategy;
        return copy;
    }

    public synchronized SudokuCellView toImmutableView() { return new SudokuCellView(this); }

    @Override public synchronized boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SudokuCell c)) return false;
        return value == c.value && isGiven == c.isGiven && hintStrength == c.hintStrength
            && pencilMarks.equals(c.pencilMarks) && conflicts.equals(c.conflicts)
            && moveSource == c.moveSource && Objects.equals(strategy, c.strategy);
    }

    @Override public synchronized int hashCode() {
        return Objects.hash(value, isGiven, hintStrength, pencilMarks, conflicts, moveSource, strategy);
    }

    @Override public synchronized String toString() {
        StringBuilder sb = new StringBuilder(value == 0 ? "." : String.valueOf(value));
        if (!pencilMarks.isEmpty()) sb.append(pencilMarks);
        if (!conflicts.isEmpty()) sb.append("C");
        if (strategy != null) sb.append("S:").append(strategy);
        return sb.toString();
    }

    public enum MoveSource { PLAYER, HINT, AUTOSOLVE, INITIAL, CHAOS, UNKNOWN }

    public enum Strategy {
        NAKED_SINGLE, HIDDEN_SINGLE, NAKED_PAIR, HIDDEN_PAIR, POINTING_PAIR,
        X_WING, Y_WING, SWORD_FISH, MANUAL, COSMIC, STARFORGE, UNKNOWN
    }
}
