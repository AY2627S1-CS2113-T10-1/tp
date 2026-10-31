// @@author ernestgoh-god
package scheduleflow.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import scheduleflow.model.Snapshot;
import scheduleflow.planning.Plan;

/**
 * Owns UTF-8 loading and atomic file replacement at a fixed absolute path.
 */
public final class FileStorage implements Storage {
    /**
     * Records opt-in storage stages without paths, saved text or exception payloads.
     * Failures remain available to callers through {@link StorageException}.
     */
    private static final Logger LOGGER = Logger.getLogger(FileStorage.class.getName());

    /**
     * Identifies the normalized absolute target, independent of later working-directory changes.
     */
    private final Path file;

    /**
     * Validates complete snapshots and preserves the versioned text format.
     */
    private final StateCodec codec;

    /** Supplies real filesystem operations, replaceable only by package-local fault tests. */
    private final FileOperations operations;

    /**
     * Creates the FileStorage dependencies without performing I/O.
     *
     * @param file target file, resolved to an absolute path at construction
     * @param codec converter used for both saving and loading
     * @throws NullPointerException if either dependency is null
     */
    public FileStorage(Path file, StateCodec codec) {
        this(file, codec, new FileOperations());
    }

    /** Creates storage with controlled save operations without touching the filesystem. */
    FileStorage(Path file, StateCodec codec, FileOperations operations) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
        this.operations = Objects.requireNonNull(operations, "operations");
    }

    /**
     * Reads UTF-8 and validates the entire snapshot without modifying the saved file.
     * A definitely absent target returns an empty snapshot; other failures never do.
     *
     * @return complete restored state, including counters and overdue tasks
     * @throws StorageException if the target cannot be read or its contents are invalid
     */
    @Override
    public Snapshot load() throws StorageException {
        LOGGER.log(Level.FINE, "Starting saved data load");
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            LOGGER.log(Level.FINE, "Saved data read; validating snapshot");
            Snapshot loaded = codec.decode(text);
            LOGGER.log(Level.FINE, "Saved data load completed");
            return loaded;
        } catch (NoSuchFileException exception) {
            // A broken link or unknown access status must not silently become an empty store.
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                LOGGER.log(Level.FINE, "Saved data absent; returning empty snapshot");
                return Snapshot.empty();
            }
            LOGGER.log(Level.FINE, "Saved data load failed; target is not definitely absent");
            throw new StorageException("Cannot load saved data: " + file, exception);
        } catch (StorageException exception) {
            LOGGER.log(Level.FINE, "Saved data load rejected; invalid contents");
            throw exception;
        } catch (IOException | SecurityException exception) {
            LOGGER.log(Level.FINE, "Saved data load failed during file access");
            throw new StorageException("Cannot load saved data: " + file, exception);
        }
    }

    /** Restores only a plan whose embedded source equals the snapshot already loaded at startup. */
    @Override
    public Optional<Plan> loadPlan(Snapshot source) throws StorageException {
        Objects.requireNonNull(source, "source");
        LOGGER.log(Level.FINE, "Starting frozen plan load");
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (!PlanCodec.hasSavedPlan(text)) {
                if (!codec.decode(text).equals(source)) {
                    throw new StorageException("Saved data does not match the loaded snapshot.");
                }
                LOGGER.log(Level.FINE, "Saved data contains no frozen plan");
                return Optional.empty();
            }
            Plan plan = new PlanCodec(codec).decode(text);
            if (!plan.source().equals(source)) {
                throw new StorageException("Saved plan does not match the loaded data.");
            }
            LOGGER.log(Level.FINE, "Frozen plan load completed");
            return Optional.of(plan);
        } catch (NoSuchFileException exception) {
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                if (!source.equals(Snapshot.empty())) {
                    throw new StorageException("Saved data is missing for the loaded snapshot.");
                }
                LOGGER.log(Level.FINE, "Saved data absent; no frozen plan to load");
                return Optional.empty();
            }
            throw new StorageException("Cannot load saved data: " + file, exception);
        } catch (IOException | SecurityException exception) {
            throw new StorageException("Cannot load saved data: " + file, exception);
        }
    }

    /**
     * Writes a sibling temporary file and atomically replaces the target before returning.
     * A failed write or replacement attempts cleanup and retains the original failure.
     *
     * @param state complete candidate to persist before publication in memory
     * @throws StorageException if preparing, writing or atomically replacing the file fails
     * @throws NullPointerException if the candidate is null, before any filesystem changes
     */
    @Override
    public void save(Snapshot state) throws StorageException {
        Objects.requireNonNull(state, "state");
        LOGGER.log(Level.FINE, "Starting snapshot save");
        String text = codec.encode(state);
        writeAtomically(text);
    }

    /** Saves a plan and its candidate source in one replacement, including newly materialized recurring tasks. */
    @Override
    public void savePlan(Plan plan) throws StorageException {
        Objects.requireNonNull(plan, "plan");
        LOGGER.log(Level.FINE, "Starting frozen plan save");
        writeAtomically(new PlanCodec(codec).encode(plan));
    }

    /** Replaces the sole data file atomically for both snapshots and plans. */
    private void writeAtomically(String text) throws StorageException {
        Path temporaryFile = null;
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("The save target must be a file, not a filesystem root.");
            }
            LOGGER.log(Level.FINE, "Preparing save directory");
            Files.createDirectories(parent);
            // Write beside the target so atomic replacement uses the same filesystem.
            temporaryFile = operations.createTemporaryFile(parent);
            assert temporaryFile.getParent().equals(parent) && !temporaryFile.equals(file)
                    : "Atomic saving requires a distinct sibling temporary file";
            LOGGER.log(Level.FINE, "Writing temporary saved data");
            operations.write(temporaryFile, text);
            LOGGER.log(Level.FINE, "Replacing saved data atomically");
            operations.replace(temporaryFile, file);
            LOGGER.log(Level.FINE, "Snapshot save completed");
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            LOGGER.log(Level.FINE, "Snapshot save failed; attempting temporary file cleanup");
            StorageException failure = new StorageException("Cannot save data: " + file, exception);
            removeTemporaryFile(temporaryFile, failure);
            throw failure;
        }
    }

    /**
     * Removes an abandoned temporary file, attaching cleanup errors to the original save failure.
     *
     * @param temporaryFile created sibling, or null if creation did not complete
     * @param failure original exception to preserve and augment if cleanup fails
     */
    private void removeTemporaryFile(Path temporaryFile, StorageException failure) {
        if (temporaryFile == null) {
            LOGGER.log(Level.FINE, "No temporary saved data to clean up");
            return;
        }
        try {
            operations.deleteIfExists(temporaryFile);
            LOGGER.log(Level.FINE, "Temporary saved data cleanup completed");
        } catch (IOException | SecurityException exception) {
            // Keep the original save failure while recording any cleanup problem as well.
            failure.addSuppressed(exception);
            LOGGER.log(Level.FINE, "Temporary saved data cleanup failed; original failure retained");
        }
    }

    /** Keeps the save transaction on the real Files API while permitting deterministic local faults. */
    static class FileOperations {
        /** Creates a distinct temporary sibling on the target filesystem. */
        Path createTemporaryFile(Path parent) throws IOException {
            return Files.createTempFile(parent, ".scheduleflow-", ".tmp");
        }

        /** Writes the complete UTF-8 candidate before any replacement is attempted. */
        void write(Path temporaryFile, String text) throws IOException {
            Files.writeString(temporaryFile, text, StandardCharsets.UTF_8);
        }

        /** Requires atomic replacement; unsupported providers must fail without a fallback. */
        void replace(Path temporaryFile, Path target) throws IOException {
            Files.move(temporaryFile, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }

        /** Removes an abandoned temporary sibling if it still exists. */
        void deleteIfExists(Path temporaryFile) throws IOException {
            Files.deleteIfExists(temporaryFile);
        }
    }
}
// @@author
