package catalog;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TeamsTest {

    private static final Teams TEAMS = Teams.load();

    @Test
    void findsATeamByLeagueQualifiedId() {
        Teams.Team chw = TEAMS.find("mlb-chw").orElseThrow();
        assertEquals("Chicago White Sox", chw.displayName());
        assertEquals("MLB", chw.league());
        assertEquals("CHW", chw.abbreviation());
    }

    @Test
    void bostonIsFourDifferentTeams() {
        // The bug this phase fixes: sub:team:BOS meant the Celtics, the Red Sox, and the Bruins at once.
        assertEquals("Boston Celtics", TEAMS.find("nba-bos").orElseThrow().displayName());
        assertEquals("Boston Red Sox", TEAMS.find("mlb-bos").orElseThrow().displayName());
        assertEquals("Boston Bruins", TEAMS.find("nhl-bos").orElseThrow().displayName());
        assertTrue(TEAMS.find("nba-lal").isPresent(), "the smoke test follows nba-lal");
    }

    @Test
    void everyLeagueIsComplete() {
        assertEquals(Map.of("NFL", 32L, "NBA", 30L, "MLB", 30L, "NHL", 32L), TEAMS.countsByLeague());
    }

    @Test
    void unknownTeamIsEmpty() {
        assertTrue(TEAMS.find("mlb-xyz").isEmpty());
        assertTrue(TEAMS.find(null).isEmpty());
    }

    @Test
    void everyTeamHasAShortName() {
        assertEquals("Red Sox", TEAMS.find("mlb-bos").orElseThrow().shortName());
        assertEquals("Bears", TEAMS.find("nfl-chi").orElseThrow().shortName());
        for (String league : TEAMS.countsByLeague().keySet()) assertNotNull(league);
        assertTrue(TEAMS.all().stream().allMatch(t -> t.shortName() != null && !t.shortName().isBlank()));
    }

    @Test
    void everyTeamHasLogoAndColors() {
        for (Teams.Team t : TEAMS.all()) {
            assertNotNull(t.logoUrl(), t.id());
            assertTrue(t.logoUrl().startsWith("https://"), t.id() + " " + t.logoUrl());
            assertNotNull(t.primaryColor(), t.id());
            assertTrue(t.primaryColor().matches("#[0-9a-fA-F]{6}"), t.id() + " " + t.primaryColor());
            assertNotNull(t.location(), t.id());
            assertNotNull(t.name(), t.id());
        }
        Teams.Team bears = TEAMS.find("nfl-chi").orElseThrow();
        assertEquals("Chicago", bears.location());
        assertEquals("Bears", bears.name());
    }
}
