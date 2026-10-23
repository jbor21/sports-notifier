package gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** #51: only the checker, holding the generated key, may sign up a {@code ck_} name. */
class CheckerNamesTest {

    @Test
    void ordinaryNameNeedsNoKey() {
        assertTrue(CheckerNames.allowed("jacob", null, ""));
    }

    @Test
    void ckNameWithoutKeyRefused() {
        assertFalse(CheckerNames.allowed("ck_abcdef_1", null, "s3cret"));
    }

    @Test
    void ckNameWrongKeyRefused() {
        assertFalse(CheckerNames.allowed("ck_abcdef_1", "nope", "s3cret"));
    }

    @Test
    void ckNameRightKeyAllowed() {
        assertTrue(CheckerNames.allowed("ck_abcdef_1", "s3cret", "s3cret"));
    }

    @Test
    void caseAndSpaceStillReserved() {
        assertFalse(CheckerNames.allowed(" CK_abcdef_1", null, "s3cret"));
    }

    @Test
    void noExpectedKeyRefusesEvenEmptyPresented() {
        assertFalse(CheckerNames.allowed("ck_a_1", "", ""));
    }
}
