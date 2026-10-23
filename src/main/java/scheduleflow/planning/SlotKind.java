package scheduleflow.planning;

/**
 * Distinguishes allocated, committed, available and pre-cutoff portions of a schedule.
 */
public enum SlotKind {
    TASK, BUSY, FREE, UNPLANNED, BREAK
}
