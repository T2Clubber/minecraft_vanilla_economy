package fr.vanillaeconomy.city;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BorderManagerTest {

    @Test
    void distanceToTheNearestEdge() {
        // 64x64 city centred on 0 0: edges x = -32 and 32, z = -32 and 32
        assertEquals(32, BorderManager.distanceToEdge(0, 0, -32, 32, -32, 32), "from the centre of a tier 2 city");
        assertEquals(2, BorderManager.distanceToEdge(30, 0, -32, 32, -32, 32));
        assertEquals(10, BorderManager.distanceToEdge(42, 0, -32, 32, -32, 32), "outside, facing a wall");
        assertEquals(15, BorderManager.distanceToEdge(41, 44, -32, 32, -32, 32), "outside, near a corner");
    }
}
