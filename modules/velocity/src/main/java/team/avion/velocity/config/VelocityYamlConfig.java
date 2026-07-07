package team.avion.velocity.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

public final class VelocityYamlConfig {
    private final YamlConfigurationLoader loader;
    private CommentedConfigurationNode root;

    public VelocityYamlConfig(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        this.loader = YamlConfigurationLoader.builder().path(path).build();
        this.root = loader.load();
    }

    public void reload() throws IOException {
        root = loader.load();
    }

    public void save() throws IOException {
        loader.save(root);
    }

    public String getString(String key) {
        return node(key).getString("");
    }

    public String getString(String key, String fallback) {
        return node(key).getString(fallback);
    }

    public int getInt(String key) {
        return node(key).getInt();
    }

    public int getInt(String key, int fallback) {
        return node(key).getInt(fallback);
    }

    public boolean getBoolean(String key) {
        return node(key).getBoolean();
    }

    public boolean getBoolean(String key, boolean fallback) {
        return node(key).getBoolean(fallback);
    }

    public List<String> getStringList(String key) throws SerializationException {
        return node(key).getList(String.class, List.of());
    }

    public void set(String key, Object value) throws SerializationException {
        node(key).set(value);
    }

    public void mergeMissingFrom(String content) throws IOException {
        YamlConfigurationLoader templateLoader = YamlConfigurationLoader.builder()
                .source(() -> new BufferedReader(new StringReader(content)))
                .build();
        CommentedConfigurationNode templateRoot = templateLoader.load();
        mergeMissing(root, templateRoot);
    }

    public boolean contains(String key) {
        return !node(key).virtual();
    }

    private CommentedConfigurationNode node(String key) {
        return root.node((Object[]) key.split("\\."));
    }

    private static void mergeMissing(CommentedConfigurationNode target, CommentedConfigurationNode defaults)
            throws SerializationException {
        if (!defaults.childrenMap().isEmpty()) {
            for (Object key : defaults.childrenMap().keySet()) {
                mergeMissing(target.node(key), defaults.node(key));
            }
            return;
        }

        if (target.virtual()) {
            target.set(defaults.raw());
        }
    }
}
