package scheduleflow.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import scheduleflow.model.Snapshot;

/**
 * Owns UTF-8 loading and atomic file replacement at a fixed absolute path.
 */
public final class FileStorage implements Storage {
    private final Path file;
    private final StateCodec codec;

    /**
     * Creates the FileStorage dependencies without performing I/O.
     */
    public FileStorage(Path file, StateCodec codec) {
        this.file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    @Override
    public Snapshot load() throws StorageException {
        try {
            return codec.decode(Files.readString(file, StandardCharsets.UTF_8));
        } catch (NoSuchFileException exception) {
            // A broken link or unknown access status must not silently become an empty store.
            if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
                return Snapshot.empty();
            }
            throw new StorageException("Cannot load saved data: " + file, exception);
        } catch (IOException | SecurityException exception) {
            throw new StorageException("Cannot load saved data: " + file, exception);
        }
    }

    @Override
    public void save(Snapshot state) throws StorageException {
        String text = codec.encode(state);
        Path temporaryFile = null;
        try {
            Path parent = file.getParent();
            if (parent == null) {
                throw new IOException("The save target must be a file, not a filesystem root.");
            }
            Files.createDirectories(parent);
            // Write beside the target so atomic replacement uses the same filesystem.
            temporaryFile = Files.createTempFile(parent, ".scheduleflow-", ".tmp");
            Files.writeString(temporaryFile, text, StandardCharsets.UTF_8);
            Files.move(temporaryFile, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | SecurityException | UnsupportedOperationException exception) {
            StorageException failure = new StorageException("Cannot save data: " + file, exception);
            removeTemporaryFile(temporaryFile, failure);
            throw failure;
        }
    }

    private void removeTemporaryFile(Path temporaryFile, StorageException failure) {
        if (temporaryFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(temporaryFile);
        } catch (IOException | SecurityException exception) {
            // Keep the original save failure while recording any cleanup problem as well.
            failure.addSuppressed(exception);
        }
    }
}
