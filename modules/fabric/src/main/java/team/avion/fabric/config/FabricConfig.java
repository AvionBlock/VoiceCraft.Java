package team.avion.fabric.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

public final class FabricConfig {
    private final Path path;
    private final Yaml yaml;
    private Map<String, Object> root = new LinkedHashMap<>();

    public FabricConfig(Path path) {
        this.path = path;
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        this.yaml = new Yaml(options);
    }

    public void load() {
        if (!Files.exists(path)) {
            root = new LinkedHashMap<>();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            Object loaded = yaml.load(reader);
            root = loaded instanceof Map<?, ?> map ? toStringMap(map) : new LinkedHashMap<>();
        } catch (IOException exception) {
            root = new LinkedHashMap<>();
        }
    }

    public void save() {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                yaml.dump(root, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to save Fabric config " + path, exception);
        }
    }

    public boolean getBoolean(String key, boolean fallback) {
        Object value = get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    public int getInt(String key, int fallback) {
        Object value = get(key);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    public long getLong(String key, long fallback) {
        Object value = get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }

    public String getString(String key, String fallback) {
        Object value = get(key);
        return value instanceof String string ? string : fallback;
    }

    public List<String> getStringList(String key) {
        Object value = get(key);
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    public void set(String key, Object value) {
        String[] parts = key.split("\\.");
        Map<String, Object> current = root;
        for (int index = 0; index < parts.length - 1; index++) {
            Object child = current.get(parts[index]);
            if (!(child instanceof Map<?, ?> map)) {
                Map<String, Object> next = new LinkedHashMap<>();
                current.put(parts[index], next);
                current = next;
            } else {
                Map<String, Object> next = toStringMap(map);
                current.put(parts[index], next);
                current = next;
            }
        }
        current.put(parts[parts.length - 1], value);
    }

    public void mergeMissingFrom(String yamlText) {
        Object loaded = yaml.load(yamlText);
        if (loaded instanceof Map<?, ?> map) mergeMissing(root, toStringMap(map));
    }

    private static void mergeMissing(Map<String, Object> target, Map<String, Object> defaults) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            Object current = target.get(entry.getKey());
            if (current == null) {
                target.put(entry.getKey(), entry.getValue());
            } else if (current instanceof Map<?, ?> currentMap && entry.getValue() instanceof Map<?, ?> defaultMap) {
                Map<String, Object> normalized = toStringMap(currentMap);
                target.put(entry.getKey(), normalized);
                mergeMissing(normalized, toStringMap(defaultMap));
            }
        }
    }

    private Object get(String key) {
        Object current = root;
        for (String part : key.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    private static Map<String, Object> toStringMap(Map<?, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : input.entrySet()) {
            if (entry.getKey() != null) {
                output.put(entry.getKey().toString(), entry.getValue());
            }
        }
        return output;
    }
}
