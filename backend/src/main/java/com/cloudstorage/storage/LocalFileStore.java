package com.cloudstorage.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One storage node: keeps named pieces of data (a key is a chunk or a manifest) in one directory.
 * <pre>
 *   {root}/hot/{key}      plain bytes
 *   {root}/cold/{key}.gz  gzip-compressed bytes
 * </pre>
 * It knows nothing about files, chunks or other nodes.
 */
public class LocalFileStore implements NodeStore {

    private final Path hotDirectory;
    private final Path coldDirectory;

    public LocalFileStore(Path root) {
        try {
            this.hotDirectory = Files.createDirectories(root.resolve("hot"));
            this.coldDirectory = Files.createDirectories(root.resolve("cold"));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage directories", e);
        }
    }

    @Override
    public void write(String key, byte[] data) {
        try {
            Files.write(hotPath(key), data);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + key, e);
        }
    }

    /** Opens the original (uncompressed) bytes, whichever tier they are in. */
    @Override
    public InputStream open(String key) {
        try {
            StorageTier tier = tierOf(key);
            if (tier == null) {
                throw new java.nio.file.NoSuchFileException(key);
            }
            return tier == StorageTier.HOT
                    ? Files.newInputStream(hotPath(key))
                    : new GZIPInputStream(Files.newInputStream(coldPath(key)));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open " + key, e);
        }
    }

    @Override
    public byte[] readAll(String key) {
        try (InputStream in = open(key)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + key, e);
        }
    }

    /** The tier this node holds the key in, or null if it does not hold it. */
    @Override
    public StorageTier tierOf(String key) {
        if (Files.exists(hotPath(key))) {
            return StorageTier.HOT;
        }
        return Files.exists(coldPath(key)) ? StorageTier.COLD : null;
    }

    @Override
    public boolean contains(String key) {
        return tierOf(key) != null;
    }

    /** Size on disk, in whichever tier the key is. */
    @Override
    public long sizeOf(String key) {
        StorageTier tier = tierOf(key);
        if (tier == null) {
            throw new UncheckedIOException(new java.nio.file.NoSuchFileException(key));
        }
        try {
            return Files.size(pathFor(key, tier));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Moves the key to the target tier (no-op if it is already there) and returns its size on disk. */
    @Override
    public long move(String key, StorageTier to) {
        StorageTier from = tierOf(key);
        if (from == null) {
            throw new UncheckedIOException(new java.nio.file.NoSuchFileException(key));
        }
        if (from == to) {
            return sizeOf(key);
        }
        try {
            if (to == StorageTier.COLD) {
                try (InputStream in = Files.newInputStream(hotPath(key));
                     OutputStream out = new GZIPOutputStream(Files.newOutputStream(coldPath(key)))) {
                    in.transferTo(out);
                }
                Files.delete(hotPath(key));
            } else {
                try (InputStream in = new GZIPInputStream(Files.newInputStream(coldPath(key)))) {
                    Files.copy(in, hotPath(key), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.delete(coldPath(key));
            }
            return sizeOf(key);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot move " + key + " to " + to, e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(hotPath(key));
            Files.deleteIfExists(coldPath(key));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + key, e);
        }
    }

    /** Copies another node's stored bytes as they are (compressed stays compressed). */
    @Override
    public void copyFrom(NodeStore from, String key) {
        if (!(from instanceof LocalFileStore source)) {
            NodeStore.super.copyFrom(from, key);
            return;
        }
        StorageTier tier = source.tierOf(key);
        if (tier == null) {
            throw new UncheckedIOException(new java.nio.file.NoSuchFileException(key));
        }
        try {
            Files.copy(source.pathFor(key, tier), pathFor(key, tier), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot copy " + key, e);
        }
    }

    /** Every key this node holds. */
    @Override
    public Set<String> keys() {
        Set<String> keys = new HashSet<>();
        list(hotDirectory, "", keys);
        list(coldDirectory, ".gz", keys);
        return keys;
    }

    @Override
    public long usedBytes() {
        long total = 0;
        for (String key : keys()) {
            if (contains(key)) {
                total += sizeOf(key);
            }
        }
        return total;
    }

    private static void list(Path directory, String suffix, Set<String> keys) {
        try (var entries = Files.list(directory)) {
            entries.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(suffix))
                    .forEach(name -> keys.add(name.substring(0, name.length() - suffix.length())));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path pathFor(String key, StorageTier tier) {
        return tier == StorageTier.HOT ? hotPath(key) : coldPath(key);
    }

    private Path hotPath(String key) {
        return hotDirectory.resolve(key);
    }

    private Path coldPath(String key) {
        return coldDirectory.resolve(key + ".gz");
    }
}
