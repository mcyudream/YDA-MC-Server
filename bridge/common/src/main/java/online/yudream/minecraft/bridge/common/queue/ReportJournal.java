package online.yudream.minecraft.bridge.common.queue;

import online.yudream.minecraft.bridge.common.json.JsonValue;
import online.yudream.minecraft.bridge.common.log.LogSink;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A JSON-lines journal of reports that have not been delivered yet.
 *
 * <p>The bridge runs on a proxy, and a proxy restart or a YuDream Admin outage used to lose every
 * queued report. The journal is a single append-and-rewrite file: pending tasks survive a restart,
 * it is human readable when an operator has to look at it, and it needs no database driver on the
 * proxy. Delivery stays at-least-once: a crash between a successful POST and the journal rewrite
 * can replay one report, which the Admin side already tolerates for join reports.
 */
public final class ReportJournal {

    private final Path path;
    private final LogSink log;

    /** @param path the journal file, or {@code null} to disable persistence entirely */
    public ReportJournal(Path path, LogSink log) {
        this.path = path;
        this.log = log;
    }

    public boolean isEnabled() {
        return path != null;
    }

    public Path getPath() {
        return path;
    }

    public List<ReportTask> load() {
        List<ReportTask> tasks = new ArrayList<ReportTask>();
        if (path == null || !Files.isRegularFile(path)) {
            return tasks;
        }
        int skipped = 0;
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                JsonValue node = JsonValue.tryParse(trimmed);
                ReportTask task = node == null ? null : ReportTask.fromJson(node);
                if (task == null) {
                    skipped++;
                } else {
                    tasks.add(task);
                }
            }
        } catch (IOException e) {
            log.warn("Could not read the YuDream report journal at " + path + ": " + e.getMessage());
            return tasks;
        }
        if (!tasks.isEmpty() || skipped > 0) {
            log.info("Recovered " + tasks.size() + " pending YuDream report(s) from " + path
                    + (skipped > 0 ? " (" + skipped + " unusable line(s) dropped)" : ""));
        }
        return tasks;
    }

    /** Rewrites the journal so it holds exactly the given pending tasks. */
    public void save(Collection<ReportTask> tasks) {
        if (path == null) {
            return;
        }
        StringBuilder out = new StringBuilder();
        for (ReportTask task : tasks) {
            out.append(task.toJson()).append('\n');
        }
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temp, out.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Could not write the YuDream report journal at " + path + ": " + e.getMessage());
        }
    }
}
