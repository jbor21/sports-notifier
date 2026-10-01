package model;

public class User {
    private final String userId;
    private final String username;
    private final String passwordHash;
    private final long createdAt;

    public User(String userId, String username, String passwordHash, long createdAt) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.createdAt = createdAt;
    }

    public String getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public long getCreatedAt() { return createdAt; }
}
