package fr.vanillaeconomy.util;

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessageConfigTest {

    private static String plain(MessageConfig msg, String key, Object... vars) {
        return PlainTextComponentSerializer.plainText().serialize(msg.get(key, vars));
    }

    @Test
    void keysMissingFromTheServerFileUseTheJarDefaults() {
        // messages.yml of a server installed before /city rename existed, with one customised text
        YamlConfiguration server = YamlConfiguration.loadConfiguration(new StringReader(
                "cities:\n  left: \"<yellow>Au revoir {city}\"\n"));
        server.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(
                MessageConfigTest.class.getResourceAsStream("/messages.yml"), StandardCharsets.UTF_8)));
        MessageConfig msg = new MessageConfig(server, "cities");
        assertEquals("La cité Old s'appelle désormais New.", plain(msg, "renamed", "old", "Old", "city", "New"));
        assertEquals("Au revoir Paris", plain(msg, "left", "city", "Paris"), "the server's own text wins");
        assertEquals("[message manquant : cities.nope]", plain(msg, "nope"));
    }
}
