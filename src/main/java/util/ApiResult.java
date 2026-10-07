package util;

/** What a service method answers over HTTP: a status and a body, or an {@link ApiError}. */
public record ApiResult<T>(int status, T body, ApiError error) {

    public static <T> ApiResult<T> ok(int status, T body) {
        return new ApiResult<>(status, body, null);
    }

    public static <T> ApiResult<T> fail(ApiError e) {
        return new ApiResult<>(e.status(), null, e);
    }

    public boolean isOk() {
        return error == null;
    }
}
