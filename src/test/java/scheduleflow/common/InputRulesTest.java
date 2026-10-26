// @@author nickpatt
package scheduleflow.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies the validators shared by task and commitment records.
 */
class InputRulesTest {
    @Test
    void normalizeName_unicodeAndSpaces_preservesInternalText() {
        assertEquals("学习  draft | notes", InputRules.normalizeName("  学习  draft | notes\u2003"));
    }

    @Test
    void normalizeName_invalidCharacters_rejectsBeforeStripping() {
        for (String name : new String[] {null, "", " \u2003 ", "a/b", "\tA", "A\n", "a\u007fb", "a\u0085b"}) {
            assertThrows(ValidationException.class, () -> InputRules.normalizeName(name));
        }
    }

    @Test
    void requireDuration_slotMultiples_acceptsLargeValues() {
        assertDoesNotThrow(() -> InputRules.requireDuration(30));
        assertDoesNotThrow(() -> InputRules.requireDuration(Integer.MAX_VALUE - 7));
        for (int duration : new int[] {0, -30, 45, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertThrows(ValidationException.class, () -> InputRules.requireDuration(duration));
        }
    }
}
// @@author
