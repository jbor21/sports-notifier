package feed;

import model.GameSnapshot;

/**
 * One game as ESPN's schedule lists it.
 *
 * @param snap      the game as the poller would read it
 * @param venue     stadium or arena name; null if ESPN sent none
 * @param broadcast first network listed, e.g. {@code ESPN}; null if none
 */
public record ScheduleEntry(GameSnapshot snap, String venue, String broadcast) {}
