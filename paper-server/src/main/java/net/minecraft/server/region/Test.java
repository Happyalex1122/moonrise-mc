package net.minecraft.server.region;
import net.minecraft.world.level.block.state.BlockState;
public class Test {
    public static boolean check(BlockState state) {
        return state.isRandomlyTicking();
    }
    public static boolean checkFluid(net.minecraft.world.level.material.FluidState state) {
        return state.isRandomlyTicking();
    }
}
