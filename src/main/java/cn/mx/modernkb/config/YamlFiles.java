package cn.mx.modernkb.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;

public final class YamlFiles {
    private YamlFiles() {}
    public static YamlConfiguration load(Path file) {
        var yaml = new YamlConfiguration();
        yaml.options().parseComments(true);
        try { yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8)); return yaml; }
        catch (Exception e) { throw new IllegalArgumentException("Cannot load " + file + "; file preserved", e); }
    }
    public static void save(Path file, YamlConfiguration yaml) throws IOException {
        write(file, yaml.saveToString());
    }
    static void write(Path file, String text) throws IOException {
        file = file.toAbsolutePath();
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), file.getFileName() + ".", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
