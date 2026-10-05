package com.flycode.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Read-before-edit enforcement cache.
 * Tracks which files have been read (via ReadFile) so that EditFile / WriteFile
 * can refuse to modify a file that the model has never seen — enforcing a
 * "read-before-edit" discipline to prevent blind overwrites.
 */
public class FileStateCache {

    // path → mtimeMs
    private final ConcurrentHashMap<String, Long> cache = new ConcurrentHashMap<>();

    /** Record a file that was just read. Stores mtime for staleness detection. */
    public void record(String absPath, long mtimeMs) {
        cache.put(absPath, mtimeMs);
    }

    /** Update cache after a successful edit/write. Re-reads mtime from disk. */
    public void update(String absPath) {
        long mtime;
        try {
            mtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            mtime = System.currentTimeMillis();
        }
        cache.put(absPath, mtime);
    }

    /**
     * Validate that a file is safe to edit/write.
     * Returns null if OK, or an error message string if the edit should be blocked.
     */
    public String validate(String absPath) {
        Long cachedMtime = cache.get(absPath);
        if (cachedMtime == null) {
            return "Error: file has not been read yet. Read it first before editing.";
        }
        long currentMtime;
        try {
            currentMtime = Files.getLastModifiedTime(Path.of(absPath)).toMillis();
        } catch (IOException e) {
            // File might have been deleted; let the caller's own exists-check handle it
            return null;
        }
        if (currentMtime > cachedMtime) {
            return "Error: file has been modified since last read. Read it again before editing.";
        }
        return null;
    }
}
