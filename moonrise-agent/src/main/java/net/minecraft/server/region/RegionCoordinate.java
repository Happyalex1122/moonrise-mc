package net.minecraft.server.region;

import java.util.Objects;

/**
 * 16x16 청크 크기의 리전 영역을 나타내는 좌표 클래스입니다.
 * long 키 인코딩 및 디코딩 연산을 제공합니다.
 */
public final class RegionCoordinate {
    private final int x;
    private final int z;

    private RegionCoordinate(int x, int z) {
        this.x = x;
        this.z = z;
    }

    /**
     * 리전 좌표 인스턴스를 생성하는 팩토리 메서드입니다.
     */
    public static RegionCoordinate of(int x, int z) {
        return new RegionCoordinate(x, z);
    }

    /**
     * 청크 좌표를 리전 좌표로 변환하여 생성하는 팩토리 메서드입니다.
     * (1 Region = 16 x 16 Chunks)
     */
    public static RegionCoordinate fromChunk(int chunkX, int chunkZ) {
        // chunkX >> 4는 음수 좌표를 안전하게 아래 방향으로 내림(floor) 처리합니다.
        return new RegionCoordinate(chunkX >> 4, chunkZ >> 4);
    }

    /**
     * 리전 X, Z 좌표를 하나의 64비트 long 값으로 인코딩합니다.
     */
    public static long encode(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * long 키에서 리전 X 좌표를 추출(디코딩)합니다.
     */
    public static int decodeX(long key) {
        return (int) (key >> 32);
    }

    /**
     * long 키에서 리전 Z 좌표를 추출(디코딩)합니다.
     */
    public static int decodeZ(long key) {
        return (int) key;
    }

    public int getX() {
        return x;
    }

    public int getZ() {
        return z;
    }

    public long toLongKey() {
        return encode(x, z);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RegionCoordinate that = (RegionCoordinate) o;
        return x == that.x && z == that.z;
    }

    @Override
    public int hashCode() {
        return Objects.hash(x, z);
    }

    @Override
    public String toString() {
        return "RegionCoordinate[" + x + ", " + z + "]";
    }
}
