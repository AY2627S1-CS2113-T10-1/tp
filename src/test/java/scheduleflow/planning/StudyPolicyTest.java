package scheduleflow.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import scheduleflow.common.ValidationException;

/** Checks the configured durations independently of parsing and persistence. */
class StudyPolicyTest {
    @Test
    void constructor_positiveAlignedDurations_preservesValues() {
        StudyPolicy policy = new StudyPolicy(90, 30);
        assertEquals(90, policy.maxContinuousMinutes());
        assertEquals(30, policy.breakMinutes());
        assertEquals(60, new StudyPolicy(30, 60).breakMinutes());
    }

    @Test
    void constructor_invalidDurations_rejectsEitherField() {
        for (int minutes : new int[] {Integer.MIN_VALUE, -30, 0, 1, 15, 45, Integer.MAX_VALUE}) {
            assertThrows(ValidationException.class, () -> new StudyPolicy(minutes, 30));
            assertThrows(ValidationException.class, () -> new StudyPolicy(90, minutes));
        }
    }
}
