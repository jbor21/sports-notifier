package checker;

/** The run could not be set up (a call to the API kept failing): no finding about the pipeline. */
public class SetupException extends RuntimeException {
    public SetupException(String message, Throwable cause) {
        super(message, cause);
    }
}
