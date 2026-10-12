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
}
