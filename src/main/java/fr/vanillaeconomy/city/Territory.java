package fr.vanillaeconomy.city;

/**
 * Square city territory on the whole world height (pure, unit-tested).
 * A size-32 territory centred on x covers x-16 .. x+15.
 */
public record Territory(String world, int centerX, int centerZ, int size) {

    public Territory {
        if (size <= 0) {
            throw new IllegalArgumentException("size must be positive");
        }
    }

    public int minX() {
        return centerX - size / 2;
    }

    public int maxX() {
        return minX() + size - 1;
    }

    public int minZ() {
        return centerZ - size / 2;
    }

    public int maxZ() {
        return minZ() + size - 1;
    }

    public boolean contains(String world, int x, int z) {
        return this.world.equals(world) && x >= minX() && x <= maxX() && z >= minZ() && z <= maxZ();
    }

    /**
     * True when the two territories overlap or are separated by fewer than {@code gap}
     * blocks (in both axes' sense: a gap of 16 means at least 16 free blocks between them).
     */
    public boolean conflicts(Territory other, int gap) {
        if (!world.equals(other.world)) {
            return false;
        }
        int g = Math.max(0, gap);
        return minX() <= other.maxX() + g && other.minX() <= maxX() + g
                && minZ() <= other.maxZ() + g && other.minZ() <= maxZ() + g;
    }

    public Territory withSize(int newSize) {
        return new Territory(world, centerX, centerZ, newSize);
    }

    public int minChunkX() {
        return minX() >> 4;
    }

    public int maxChunkX() {
        return maxX() >> 4;
    }

    public int minChunkZ() {
        return minZ() >> 4;
    }

    public int maxChunkZ() {
        return maxZ() >> 4;
    }
}
