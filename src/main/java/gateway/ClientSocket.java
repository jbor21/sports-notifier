package gateway;

/** One open browser connection, as the gateway's routing sees it. LiveSocket adapts Javalin's WsContext. */
public interface ClientSocket {
    boolean isOpen();

    /** Blocks until the frame is written. */
    void send(String text);

    /** Queues the frame and returns at once; {@code done} runs when it is written or has failed. */
    default void sendAsync(String text, Runnable done) {
        try {
            send(text);
        } finally {
            done.run();
        }
    }

    void close(int code, String reason);
}
