package feed;

import model.GameSnapshot;

import java.io.IOException;
import java.util.List;

/**
 * Where scores come from. ESPN is the only one today; another feed is one more implementation
 * plus its own way of filling in a {@link GameSnapshot}, and nothing downstream changes.
 */
public interface ScoreFeed {

    /**
     * Every game on the league's current scoreboard.
     *
     * @param league {@code NBA}, {@code NFL}, {@code MLB}, or {@code NHL}
     * @throws FeedBlockedException when the source refuses us (403 or 429)
     */
    List<GameSnapshot> fetch(String league) throws FeedBlockedException, IOException;
}
