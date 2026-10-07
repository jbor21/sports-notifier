package gateway;

/** One open browser connection, as the gateway's routing sees it. GatewayMain adapts Javalin's WsContext. */
public interface ClientSocket {
    boolean isOpen();
    void send(String text);
    void close(int code, String reason);
}
