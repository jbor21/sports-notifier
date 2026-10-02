package feed;

/** The score source refused the request (HTTP 403 or 429). The poller backs off. */
public class FeedBlockedException extends Exception {

    private final int status;

    public FeedBlockedException(int status, String url) {
        super("HTTP " + status + " from " + url);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
