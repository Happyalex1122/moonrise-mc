package net.minecraft.server.region;

/**
 * Region-based 멀티스레드 아키텍처에서 특정 리전(Region)의 틱 연산을 수행하는 스레드입니다.
 * AsyncCatcher 검증을 우회하기 위해 Thread.currentThread() instanceof RegionThread 검사에 사용됩니다.
 */
public class RegionThread extends ca.spottedleaf.moonrise.common.util.TickThread {
    private final int regionX;
    private final int regionZ;
    private final String worldName;

    public RegionThread(Runnable target, String name, String worldName, int regionX, int regionZ) {
        super(target, name);
        this.worldName = worldName;
        this.regionX = regionX;
        this.regionZ = regionZ;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getRegionX() {
        return regionX;
    }

    public int getRegionZ() {
        return regionZ;
    }
    
    @Override
    public String toString() {
        return "RegionThread{" +
                "world='" + worldName + '\'' +
                ", rX=" + regionX +
                ", rZ=" + regionZ +
                ", name=" + getName() +
                '}';
    }
}
