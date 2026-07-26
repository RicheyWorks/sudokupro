package com.xai.sudokupro.client.net;

import java.util.function.Consumer;

/** Opens a {@link GameLink} for one game. {@code ServerApi::openSocket} is the production implementation. */
@FunctionalInterface
public interface GameLinkFactory {

    /**
     * Opens and completes the handshake, or throws.
     *
     * @throws ApiException when the channel could not be established
     */
    GameLink open(String gameId, Consumer<Envelope> onEnvelope, CloseListener onClose);
}
