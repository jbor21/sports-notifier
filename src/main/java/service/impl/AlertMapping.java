package service.impl;

import catalog.AlertCatalog;

import java.util.List;

/**
 * Until 3.3's detectors, Stage 1 still receives the three 3.1 play types. This maps each onto the
 * catalog switches that follows carry (spec section 6). A play carries only the score after it, so
 * an NFL touchdown and a field goal look alike: any NFL scoring switch hears both, headlined SCORE.
 */
public final class AlertMapping {

    private final AlertCatalog catalog;

    public AlertMapping(AlertCatalog catalog) {
        this.catalog = catalog;
    }

    /** The switches whose sets make up this play's audience; empty means nobody hears about it. */
    public List<String> switchesFor(String league, String eventType) {
        if (league == null || catalog.forLeague(league).isEmpty() || eventType == null) return List.of();
        return switch (eventType) {
            case "GAME_START" -> List.of("GAME_START");
            case "GAME_END" -> List.of("FINAL");
            case "SCORE_UPDATE" -> catalog.scoringTypes(league);   // NBA has none: no alert per basket
            default -> List.of();
        };
    }

    /** The alert type history shows as the headline. */
    public String headlineType(String league, String eventType) {
        if (eventType == null) return null;
        return switch (eventType) {
            case "GAME_START" -> "GAME_START";
            case "GAME_END" -> "FINAL";
            case "SCORE_UPDATE" -> {
                List<String> scoring = catalog.scoringTypes(league);
                yield scoring.size() == 1 ? scoring.get(0) : "SCORE";
            }
            default -> eventType;
        };
    }
}
