-- V3: persist the actual 9x9 grid (save/load feature).
-- The SudokuCell[][] grid is @Transient in JPA; until now a board loaded back
-- from the database came back with a blank grid. cells_json stores the compact
-- per-cell snapshot produced by SudokuBoard.snapshotCells() (values, given
-- flags, move sources, pencil marks, conflicts) and is rebuilt into the live
-- grid by the entity's @PostLoad callback. Nullable: rows written before this
-- migration have no snapshot and simply cannot be resumed.
alter table sudoku_boards add column cells_json text;
