#!/usr/bin/env python3
"""
Mutation harness for the JavaFX client's test suite.

Applies one deliberate defect to production code at a time, runs the client
tests, and records which test methods notice. A mutation that nothing notices is
a test that cannot fail; a mutation that the *wrong* test notices is a test that
is coupled to something other than what it claims to cover.

Usage:  python3 testing/mutation_check_client.py [substring-of-mutation-id ...]
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "client/src/main/java/com/xai/sudokupro")

# (id, relative-path, find, replace, expected-failing-test-substrings)
MUTATIONS = [
    ("M01-close-before-open",
     "client/net/GameChannel.java",
     "        GameLink fresh = factory.open(targetGameId, envelopes, attachment);",
     "        closeQuietly(this.link);\n        GameLink fresh = factory.open(targetGameId, envelopes, attachment);",
     ["aRefusedGameSwitchLeavesTheCurrentChannelConnected"]),

    ("M02-retry-1008",
     "client/net/ReconnectPolicy.java",
     "        if (closeStatus == POLICY_VIOLATION) return false;",
     "        if (false) return false;",
     ["aPolicyViolationCloseIsNotRetried", "policyViolationCloseIsNeverRetried"]),

    ("M03-no-stale-guard",
     "client/net/GameChannel.java",
     "            if (attachment != current) return;   // a link we already replaced, or a deliberate close",
     "            if (false) return;",
     ["aDeliberateCloseNeitherWarnsTheUserNorReconnects"]),

    ("M04-drop-buffered-close",
     "client/net/GameChannel.java",
     "                if (!closed) return;",
     "                if (true) return;",
     ["aCloseArrivingBeforeTheLinkIsInstalledIsNotLost"]),

    ("M05-no-resync-on-send-failure",
     "client/net/GameChannel.java",
     '        onNotice.accept("error", "Your " + type + " did not reach the server — resyncing the board");\n        onResyncNeeded.run();',
     '        onNotice.accept("error", "Your " + type + " did not reach the server — resyncing the board");',
     ["aSendThatFailsOnTheWireIsReportedAndTriggersAResync"]),

    ("M06-wrong-exception-type",
     "client/net/GameChannel.java",
     "            throw new ConnectionException(describeUnavailable(currentState), currentState);",
     "            throw new IllegalStateException(describeUnavailable(currentState));",
     ["sendOnADeadChannelThrowsAConnectionExceptionCarryingTheState"]),

    ("M07-no-scheduler-shutdown",
     "client/net/GameChannel.java",
     "        close();\n        scheduler.shutdown();",
     "        close();",
     ["shutdownReleasesTheLinkAndTheRetryScheduler"]),

    ("M08-no-resync-after-reconnect",
     "client/net/GameChannel.java",
     '            notifyState(ConnectionState.CONNECTED);\n            onResyncNeeded.run();\n        } catch (RuntimeException e) {',
     '            notifyState(ConnectionState.CONNECTED);\n        } catch (RuntimeException e) {',
     ["anUnexpectedCloseSchedulesARetryAndComesBack"]),

    ("M09-retry-auth-failure",
     "client/net/ReconnectPolicy.java",
     "        if (isPermanent(failure)) return false;",
     "        if (false) return false;",
     ["anAuthFailureDuringReconnectIsNotRetried", "authFailuresArePermanent"]),

    ("M10-no-gameid-guard",
     "client/net/GameChannel.java",
     '        if (target == null) {\n            throw new ConnectionException("No game to reconnect to — start or resume a game first");\n        }\n        doConnect(target);',
     '        doConnect(target);',
     ["reconnectNowWithoutAnyGameSaysSoRatherThanFailingObscurely"]),

    ("M11-flat-backoff",
     "client/net/ReconnectPolicy.java",
     "            millis *= multiplier;",
     "            millis *= 1.0;",
     ["retryingStopsAfterTheBudgetAndLandsInFailed", "delayGrowsGeometrically"]),

    ("M12-unbounded-attempts",
     "client/net/ReconnectPolicy.java",
     "        return attempt <= maxAttempts;\n    }\n\n    /** Whether attempt {@code attempt} should be made after a reconnect threw {@code failure}. */",
     "        return true;\n    }\n\n    /** Whether attempt {@code attempt} should be made after a reconnect threw {@code failure}. */",
     ["retryingStopsAfterTheBudgetAndLandsInFailed", "stopsAtTheAttemptBudget"]),

    ("M13-delay-cap-ignored",
     "client/net/ReconnectPolicy.java",
     "        return Duration.ofMillis(Math.min(maxMillis, (long) millis));",
     "        return Duration.ofMillis((long) millis);",
     ["delayIsCapped"]),

    # ---- GameSocket ----
    ("M14-unchained-sends",
     "client/net/GameSocket.java",
     "            sendChain = sendChain\n                .thenCompose(ignored -> ws.sendText(json, true))",
     "            sendChain = CompletableFuture.completedFuture((Object) null)\n                .thenCompose(ignored -> ws.sendText(json, true))",
     ["sendsAreSerialisedOntoThePreviousSend"]),

    ("M15-discard-send-future",
     "client/net/GameSocket.java",
     "                    if (failure != null) reportSendFailure(type, failure);",
     "                    if (false) reportSendFailure(type, failure);",
     ["aSendThatFailsOnTheWireIsReportedToTheListener"]),

    ("M16-poisoned-chain",
     "client/net/GameSocket.java",
     "                .handle((ignored, failure) -> {\n                    if (failure != null) reportSendFailure(type, failure);\n                    return (Object) null;\n                });",
     "                .whenComplete((ignored, failure) -> {\n                    if (failure != null) reportSendFailure(type, failure);\n                });",
     ["aFailedSendDoesNotBlockTheNextOne"]),

    ("M17-double-close-report",
     "client/net/GameSocket.java",
     "        if (!closeReported.compareAndSet(false, true)) return;",
     "        if (false) return;",
     ["oneDeadLinkIsReportedExactlyOnce", "aDeliberateCloseIsNotReportedAsAConnectionLoss"]),

    ("M18-unbounded-partial-buffer",
     "client/net/GameSocket.java",
     "        if (partial.length() + data.length() > MAX_MESSAGE_CHARS) {",
     "        if (false) {",
     ["anEndlessMessageIsDroppedRatherThanBufferedForever"]),

    # ---- ServerApi / ServerConfig ----
    ("M19-discard-403-body",
     "client/net/ServerApi.java",
     "        if (status == 403) {\n            return serverDetail != null\n                ? serverDetail\n                : \"Access denied (403) for \" + uri.getPath();\n        }",
     "        if (status == 403) {\n            return \"Access denied (403) for \" + uri.getPath();\n        }",
     ["forbiddenBodyReachesTheUser"]),

    ("M20-discard-401-body",
     "client/net/ServerApi.java",
     "        if (status == 401) {\n            return serverDetail != null\n                ? serverDetail + \" (check username/password)\"\n                : \"Authentication failed — check username/password.\";\n        }",
     "        if (status == 401) {\n            return \"Authentication failed — check username/password.\";\n        }",
     ["unauthorizedBodyReachesTheUser"]),

    ("M21-no-ws-encoding",
     "client/net/ServerConfig.java",
     '        return URI.create(gameId == null ? ws : ws + "?gameId=" + encodeQueryValue(gameId));',
     '        return URI.create(gameId == null ? ws : ws + "?gameId=" + gameId);',
     ["wsUriPercentEncodesTheGameId"]),

    # ---- PlayClock ----
    ("M22-pause-does-not-pause",
     "client/PlayClock.java",
     "        long end = paused ? pausedAt : ticker.nowMillis();\n        return Math.max(0L, end - startedAt - pausedTotal);",
     "        return Math.max(0L, ticker.nowMillis() - startedAt);",
     ["pausedTimeIsNotCharged", "elapsedIsFrozenWhilePaused"]),

    ("M23-resume-forgets-the-break",
     "client/PlayClock.java",
     "        pausedTotal += ticker.nowMillis() - pausedAt;",
     "        pausedTotal += 0;",
     ["pausedTimeIsNotCharged"]),

    ("M24-double-pause-extends-break",
     "client/PlayClock.java",
     "        if (!running || paused) return;",
     "        if (!running) return;",
     ["asecondPauseIsANoOp", "aSecondPauseIsANoOp"]),

    ("M25-format-drops-minutes",
     "client/PlayClock.java",
     '        return String.format("%02d:%02d", safe / 60_000, (safe % 60_000) / 1000);',
     '        return String.format("%02d:%02d", (safe / 60_000) % 60, (safe % 60_000) / 1000);',
     ["formatDoesNotWrapMinutesAtSixty"]),

    # ---- ReplaySession ----
    ("M26-replay-newest-first",
     "client/ReplaySession.java",
     "        List<EnhancedMove> history = board.getReplayHistory();\n        return new ReplaySession(history == null ? List.of() : new ArrayList<>(history));",
     "        java.util.Deque<com.xai.sudokupro.model.SudokuBoard.Move> history = board.getMoveHistory();\n"
     "        List<EnhancedMove> converted = new ArrayList<>();\n"
     "        for (com.xai.sudokupro.model.SudokuBoard.Move m : history) {\n"
     "            converted.add(new EnhancedMove(m.row(), m.col(), m.oldVal(), m.newVal(), m.source()));\n"
     "        }\n"
     "        return new ReplaySession(converted);",
     ["replaysThePlayersMovesOldestFirst"]),

    ("M27-replay-never-finishes",
     "client/ReplaySession.java",
     "        return index < moves.size();",
     "        return true;",
     ["stepsThroughEveryMoveExactlyOnce"]),

    # ---- MoveLabels / ChatLine ----
    ("M28-hint-label-drift",
     "client/MoveLabels.java",
     "        return HINT.equals(historyLine);",
     '        return "Hint".equals(historyLine);',
     ["hintMovesUseTheSameLabelTheFilterMatches"]),

    ("M29-zero-based-coordinates",
     "client/MoveLabels.java",
     '        return String.format("(%d,%d)=%d", move.row() + 1, move.col() + 1, move.newVal());',
     '        return String.format("(%d,%d)=%d", move.row(), move.col(), move.newVal());',
     ["coordinatesAreOneBasedForThePlayer"]),

    ("M30-chat-double-label",
     "client/ChatLine.java",
     '        return "[" + CLOCK.format(at) + "] " + speaker + ": " + text;',
     '        return "[" + CLOCK.format(at) + "] " + speaker + ": " + speaker + ": " + text;',
     ["rendersOneTimestampOneSpeakerAndTheMessage"]),

    # ---- ThemeManager ----
    ("M31-raw-path-fallback",
     "ui/ThemeManager.java",
     "        return url == null ? Optional.empty() : Optional.of(url.toExternalForm());",
     "        return Optional.of(url == null ? classpathPath : url.toExternalForm());",
     ["aMissingStylesheetResolvesToNothingNotToARawClasspathPath"]),

    ("M32-negative-theme-index",
     "ui/ThemeManager.java",
     "        return resolveResource(THEMES[Math.floorMod(themeIndex, THEMES.length)]);",
     "        return resolveResource(THEMES[themeIndex % THEMES.length]);",
     ["aNegativeThemeIndexDoesNotThrow"]),

    ("M33-custom-css-ignored",
     "ui/ThemeManager.java",
     "        Optional<String> custom = customStylesheet();\n        if (custom.isPresent()) return custom;",
     "        Optional<String> custom = Optional.<String>empty();\n        if (custom.isPresent()) return custom;",
     ["savedCustomCssWinsOverEveryNamedTheme"]),

    ("M34-named-theme-ignored",
     "ui/ThemeManager.java",
     "        int themeIndex = getThemeIndexFromName(preferredTheme.toLowerCase());\n        return stylesheetUrl(themeIndex != -1 ? themeIndex : DEFAULT_THEME_INDEX);",
     "        return stylesheetUrl(DEFAULT_THEME_INDEX);",
     ["aSavedNamedThemeResolvesToThatThemesStylesheet"]),

    ("M35-preset-ignored",
     "ui/ThemeManager.java",
     "            .or(() -> lookupIgnoringCase(PRESET_THEMES, preferredTheme));",
     "            .or(() -> Optional.<String>empty());",
     ["aSavedPresetThemeResolvesToItsInlineCss"]),

    ("M35b-preset-lookup-case-sensitive",
     "ui/ThemeManager.java",
     "            if (entry.getKey().equalsIgnoreCase(name)) return Optional.of(entry.getValue());",
     "            if (entry.getKey().equals(name)) return Optional.of(entry.getValue());",
     ["aPresetSavedInADifferentCaseStillResolves"]),

    ("M35c-theme-choice-not-persisted",
     "ui/ThemeManager.java",
     "        prefs.setProperty(PREF_THEME, themeName);\n        prefs.remove(PREF_CUSTOM_CSS);\n        savePrefs();",
     "        prefs.setProperty(PREF_THEME, themeName);\n        prefs.remove(PREF_CUSTOM_CSS);",
     ["aRememberedThemeSurvivesARestart"]),

    ("M35d-custom-css-not-cleared",
     "ui/ThemeManager.java",
     "        prefs.setProperty(PREF_THEME, themeName);\n        prefs.remove(PREF_CUSTOM_CSS);\n        savePrefs();",
     "        prefs.setProperty(PREF_THEME, themeName);\n        savePrefs();",
     ["rememberingANamedThemeClearsAnyCustomCss"]),

    # ---- GameClient ----
    ("M36-board-replaced-before-channel",
     "client/GameClient.java",
     "        channel.connect(state.gameId());\n        SudokuBoard adopted = state.toBoard();\n        this.board = adopted;",
     "        SudokuBoard adopted = state.toBoard();\n        this.board = adopted;\n        channel.connect(state.gameId());",
     ["aFailedSwitchLeavesTheOldBoardAndChannelInPlace"]),

    ("M37-rest-failure-still-kills-channel",
     "client/GameClient.java",
     "        BoardState state = api.getGame(gameId);\n        SudokuBoard adopted = adopt(state);\n        logger.info(\"Spectating game {}\", gameId);",
     "        closeSocket();\n        BoardState state = api.getGame(gameId);\n        SudokuBoard adopted = adopt(state);\n        logger.info(\"Spectating game {}\", gameId);",
     ["aRefusedSpectateLeavesTheChannelUsable"]),

    ("M38-optimistic-update-before-send",
     "client/GameClient.java",
     '        channel.send("move", move);\n        current.applyExternalMove(move);',
     '        current.applyExternalMove(move);\n        channel.send("move", move);',
     ["aMoveThatCannotBeSentIsNotAppliedLocally"]),

    ("M39-chat-relabelled",
     "client/GameClient.java",
     '                case "chat" -> onChat.accept(envelope.from(), envelope.payloadText());',
     '                case "chat" -> onChat.accept(envelope.from(), envelope.from() + ": " + envelope.payloadText());',
     ["incomingChatIsHandedOverUnlabelled"]),

    # ---- real WebSocket transport (GameChannelTransportTest) ----
    ("M40-handshake-status-erased",
     "client/net/GameSocket.java",
     "            return new ApiException(status, \"WebSocket connect to \" + uri + \" failed: \" + detail);",
     "            return new ApiException(\"WebSocket connect to \" + uri + \" failed: \" + detail, cause);",
     ["arefusedUpgradeArrivesAsAnApiExceptionWithTheHttpStatus"]),

    ("M41-close-status-erased",
     "client/net/GameSocket.java",
     "        reportClose(statusCode, reason);\n        return null;",
     "        reportClose(1000, reason);\n        return null;",
     ["aServerCloseAfterTheHandshakeArrivesWithItsStatusAndReason"]),

    ("M42-no-auth-header",
     "client/net/GameSocket.java",
     '                .header("Authorization", basicAuth)\n',
     "",
     ["aRealHandshakeProducesAnOpenChannelCarryingTheCredentials"]),

    ("M43-transport-error-not-reported",
     "client/net/GameSocket.java",
     '        reportClose(CloseListener.TRANSPORT_ERROR, String.valueOf(error.getMessage()));',
     "        // mutation: transport error swallowed",
     ["aTransportErrorWithNoCloseFrameIsStillReportedAsALostLink"]),

    ("M44-handshake-failure-swallowed",
     "client/net/GameSocket.java",
     "            throw handshakeFailure(uri, cause);",
     "            return socket;",
     ["anUnreachableServerFailsTheConnectRatherThanReturningADeadChannel",
      "arefusedUpgradeArrivesAsAnApiExceptionWithTheHttpStatus"]),
]


def run_tests():
    proc = subprocess.run(
        ["mvn", "-o", "-q", "-pl", "client", "surefire:test", "-Dsurefire.failIfNoSpecifiedTests=false"],
        cwd=ROOT, capture_output=True, text=True)
    return proc.stdout + proc.stderr


def failing_tests(output):
    names = set()
    for line in output.splitlines():
        m = re.match(r"\[ERROR\]\s+(\w+)\.(\w+)", line.strip())
        if m:
            names.add(m.group(2))
        m2 = re.search(r"(\w+Test)\.(\w+):", line)
        if m2:
            names.add(m2.group(2))
    return names


def main():
    wanted = sys.argv[1:]
    # compile once so surefire:test can run without a full lifecycle each time
    subprocess.run(["mvn", "-o", "-q", "-pl", "model", "install", "-DskipTests"], cwd=ROOT, check=True)
    subprocess.run(["mvn", "-o", "-q", "-pl", "client", "test-compile"], cwd=ROOT, check=True)

    results = []
    for mid, rel, find, repl, expected in MUTATIONS:
        if wanted and not any(w in mid for w in wanted):
            continue
        path = os.path.join(SRC, rel)
        original = open(path, encoding="utf-8").read()
        if find not in original:
            results.append((mid, "PATTERN-NOT-FOUND", set()))
            print(f"{mid:38s} PATTERN-NOT-FOUND ({rel})", flush=True)
            continue
        open(path, "w", encoding="utf-8").write(original.replace(find, repl, 1))
        try:
            compile_proc = subprocess.run(["mvn", "-o", "-q", "-pl", "client", "test-compile"],
                                          cwd=ROOT, capture_output=True, text=True)
            if compile_proc.returncode != 0:
                results.append((mid, "DID-NOT-COMPILE", set()))
                print(f"{mid:38s} DID-NOT-COMPILE", flush=True)
                continue
            out = run_tests()
            failed = failing_tests(out)
            hit = [e for e in expected if any(e in f for f in failed)]
            status = "CAUGHT" if hit else "SURVIVED"
            results.append((mid, status, failed))
            print(f"{mid:38s} {status:9s} failing={sorted(failed)}", flush=True)
        finally:
            open(path, "w", encoding="utf-8").write(original)

    subprocess.run(["mvn", "-o", "-q", "-pl", "client", "test-compile"], cwd=ROOT,
                   capture_output=True, text=True)
    survived = [m for m, s, _ in results if s != "CAUGHT"]
    print("\n%d mutations, %d caught, %d not caught" %
          (len(results), len(results) - len(survived), len(survived)))
    if survived:
        print("NOT CAUGHT: " + ", ".join(survived))
    return 1 if survived else 0


if __name__ == "__main__":
    sys.exit(main())
