package fr.vanillaeconomy.discord;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaffLogTest {

    @Test
    void namesAreEscapedForDiscordMarkdown() {
        assertEquals("Bob\\_the\\_builder", StaffLog.md("Bob_the_builder"));
        assertEquals("a\\*b\\~c\\`d\\|e\\>f\\\\g", StaffLog.md("a*b~c`d|e>f\\g"));
        assertEquals("Paris", StaffLog.md("Paris"));
    }

    @Test
    void connectionLines() {
        assertEquals("`14:02:11` 🟢 **Bob\\_B** connexion · 3 en ligne", StaffLog.joinLine("14:02:11", "Bob_B", false, 3));
        assertEquals("`14:02:11` 🟢 **Alex** connexion *(première connexion)* · 1 en ligne",
                StaffLog.joinLine("14:02:11", "Alex", true, 1));
        assertEquals("`15:14:40` 🔴 **Alex** déconnexion après 1 h 12 min · 0 en ligne",
                StaffLog.quitLine("15:14:40", "Alex", 72 * 60_000L, null, 0));
        assertEquals("`15:14:40` 🔴 **Alex** expulsion (Spam) après 5 min · 2 en ligne",
                StaffLog.quitLine("15:14:40", "Alex", 5 * 60_000L + 30_000, "expulsion (Spam)", 2));
        assertEquals("`15:14:40` 🔴 **Alex** déconnexion après moins d'une minute · 0 en ligne",
                StaffLog.quitLine("15:14:40", "Alex", 20_000, null, 0));
    }
}
