package com.xai.sudokupro.ui;

import com.xai.sudokupro.client.GameClient;
import com.xai.sudokupro.client.net.CloseListener;
import com.xai.sudokupro.client.net.Envelope;
import com.xai.sudokupro.client.net.GameChannel;
import com.xai.sudokupro.client.net.GameLink;
import com.xai.sudokupro.client.net.GameLinkFactory;
import com.xai.sudokupro.client.net.ReconnectPolicy;
import com.xai.sudokupro.client.net.ServerApi;
import com.xai.sudokupro.client.net.ServerConfig;
import com.xai.sudokupro.model.SudokuCell;
import com.xai.sudokupro.model.SudokuCellView;
import com.xai.sudokupro.model.api.BoardState;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * First coverage of {@link BoardView}, the desktop client's board.
 *
 * <p>Defect class: <b>"has a value" mistaken for "is a clue".</b> {@code createCell} used to
 * call {@code setEditable(value == 0)}, so every cell holding a digit was locked as if the
 * server had given it. On a fresh puzzle that is indistinguishable from correct — the only
 * filled cells ARE the clues. It becomes visible the moment a board is rebuilt with the
 * player's own work in it: resume, duel rejoin, daily join, or any {@code swapInBoard}. The
 * player got their game back with every cell they had already filled frozen, including any
 * they had filled wrongly, so the game was not merely awkward but unfinishable.
 *
 * <p>That is precisely the shape a unit test catches and a human demo does not, and this
 * package had no test at all — which is how it survived. The assertions below therefore key
 * on the distinction itself: a non-given cell that already holds a value must stay editable.
 *
 * <p>Reads the real scene graph rather than the private {@code cellFields} array, the same
 * way the web harness reads the DOM: the test should fail if the control the user actually
 * touches is wrong, not merely if a field is.
 */
class BoardViewCellStateTest {

    /** Row-major index of a cell the server marked as a clue. */
    private static final int GIVEN_INDEX = 0;
    /** A cell the PLAYER filled in — not a clue, and the heart of this test. */
    private static final int PLAYER_FILLED_INDEX = 40;
    /** A cell nobody has touched. */
    private static final int EMPTY_INDEX = 80;

    private GameClient client;
    private final List<String> notices = new ArrayList<>();

    /**
     * A board whose three interesting cells are distinct: one given, one filled by the
     * player, one empty.
     */
    private static BoardState boardWithPlayerWork(String gameId) {
        List<List<SudokuCellView>> cells = new ArrayList<>();
        for (int r = 0; r < 9; r++) {
            List<SudokuCellView> row = new ArrayList<>();
            for (int c = 0; c < 9; c++) {
                int index = r * 9 + c;
                int value = 0;
                boolean given = false;
                SudokuCell.MoveSource source = SudokuCell.MoveSource.INITIAL;
                if (index == GIVEN_INDEX) {
                    value = 5;
                    given = true;
                } else if (index == PLAYER_FILLED_INDEX) {
                    value = 7;                    // the player's own entry
                    source = SudokuCell.MoveSource.PLAYER;
                }
                row.add(new SudokuCellView(value, given, 0L, source, Set.of(), Set.of(),
                    0, SudokuCell.Strategy.UNKNOWN, !given, "ann", null));
            }
            cells.add(row);
        }
        return new BoardState(gameId, "ann", 2, false, false, false, 3, 0, 0, 0, cells);
    }

    private static final class StubApi extends ServerApi {
        StubApi() {
            super(new ServerConfig("http://localhost:1", "ann", "pw"));
        }

        @Override
        public BoardState newGame(int difficulty, boolean chaos, boolean mirror) {
            return boardWithPlayerWork("g-resumed");
        }

        @Override
        public BoardState getGame(String gameId) {
            return boardWithPlayerWork(gameId);
        }

        @Override
        public String playerId() {
            return "ann";
        }
    }

    private static final class StubLink implements GameLink {
        @Override public void send(String type, Object payload) { }
        @Override public boolean isOpen() { return true; }
        @Override public void close() { }
        @Override public void setSendFailureListener(BiConsumer<String, Throwable> l) { }
    }

    private static final class StubFactory implements GameLinkFactory {
        @Override
        public GameLink open(String gameId, Consumer<Envelope> onEnvelope, CloseListener onClose) {
            return new StubLink();
        }
    }

    @BeforeEach
    void setUp() {
        GameChannel channel = new GameChannel(new StubFactory(), envelope -> { },
            new ReconnectPolicy(2, Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0),
            (task, delay) -> { });
        client = new GameClient(new StubApi(), channel);
        channel.setOnResyncNeeded(() -> { });
        client.setNotifier((type, message) -> notices.add(type + ": " + message));
        client.newGame(2, false, false);   // populates client.board()
    }

    /** Every TextField under the board's GridPane, in the order the grid lays them out. */
    private static List<TextField> cellsOf(BoardView view) {
        List<TextField> found = new ArrayList<>();
        collect(view.getView(), found);
        return found;
    }

    private static void collect(Node node, List<TextField> into) {
        if (node instanceof GridPane grid) {
            List<Node> children = new ArrayList<>(grid.getChildren());
            children.sort((a, b) -> {
                int ra = idx(GridPane.getRowIndex(a)), rb = idx(GridPane.getRowIndex(b));
                if (ra != rb) return Integer.compare(ra, rb);
                return Integer.compare(idx(GridPane.getColumnIndex(a)), idx(GridPane.getColumnIndex(b)));
            });
            for (Node child : children) {
                if (child instanceof TextField tf) into.add(tf);
            }
            return;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) collect(child, into);
        }
    }

    private static int idx(Integer boxed) {
        return boxed == null ? 0 : boxed;
    }

    private BoardView newView() {
        return FxToolkit.onFxThread(() -> new BoardView(client, (t, m) -> notices.add(t + ": " + m)));
    }

    /**
     * The regression itself. A clue is locked; the player's own digit is not.
     *
     * <p>Under the old {@code setEditable(value == 0)} the second assertion fails: the
     * player's 7 is treated as a clue because it is non-zero.
     */
    @Test
    void aPlayersOwnEntryStaysEditableWhileACluesDoesNot() {
        BoardView view = newView();
        List<TextField> cells = cellsOf(view);

        assertEquals(81, cells.size(), "the grid must hold 81 cells");

        assertFalse(cells.get(GIVEN_INDEX).isEditable(),
            "a cell the server marked as a clue must be locked");
        assertEquals("5", cells.get(GIVEN_INDEX).getText());

        assertTrue(cells.get(PLAYER_FILLED_INDEX).isEditable(),
            "a cell the PLAYER filled must stay editable — locking it on rebuild is what made "
                + "a resumed or rejoined game unfinishable");
        assertEquals("7", cells.get(PLAYER_FILLED_INDEX).getText());

        assertTrue(cells.get(EMPTY_INDEX).isEditable(), "an untouched cell must be editable");
        assertEquals("", cells.get(EMPTY_INDEX).getText());
    }

    /**
     * {@code refresh()} re-asserts editability, so a resync that changes which cells are
     * clues cannot leave a stale lock behind. Without that, undo clearing a cell — or a
     * server resync marking a cell as no longer given — left it permanently uneditable.
     */
    @Test
    void refreshReassertsEditabilityFromTheBoard() {
        BoardView view = newView();
        List<TextField> cells = cellsOf(view);
        assertTrue(cells.get(PLAYER_FILLED_INDEX).isEditable(), "test setup");

        // The authoritative board now calls that cell a clue.
        FxToolkit.onFxThread(() -> {
            view.getBoard().getBoard()[PLAYER_FILLED_INDEX / 9][PLAYER_FILLED_INDEX % 9].setGiven(true);
            view.refresh();
        });
        FxToolkit.settle();

        assertFalse(cells.get(PLAYER_FILLED_INDEX).isEditable(),
            "refresh must take editability from the board, not from what it was at build time");
    }

    /** A clue is visually distinguished, not merely locked — colour is how a player sees it. */
    @Test
    void cluesAreStyledDifferentlyFromPlayerEntries() {
        BoardView view = newView();
        List<TextField> cells = cellsOf(view);

        assertNotEquals(cells.get(GIVEN_INDEX).getStyle(), cells.get(PLAYER_FILLED_INDEX).getStyle(),
            "a clue and a player entry that look identical leave the player unable to tell "
                + "which digits are theirs to change");
    }

    /** The grid must render the board it was given, cell for cell. */
    @Test
    void everyBoardValueReachesItsCell() {
        BoardView view = newView();
        List<TextField> cells = cellsOf(view);

        for (int r = 0; r < 9; r++) {
            for (int c = 0; c < 9; c++) {
                int value = view.getBoard().getBoard()[r][c].getValue();
                String expected = value == 0 ? "" : String.valueOf(value);
                assertEquals(expected, cells.get(r * 9 + c).getText(),
                    "cell " + r + "," + c + " does not show its board value");
            }
        }
    }
}
