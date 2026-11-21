package games.jvrcruz.siliconcraft.block;

import net.minecraft.core.BlockPos;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.block.DropExperienceBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;

public class SiliconOreBlock extends DropExperienceBlock {
    public SiliconOreBlock(BlockBehaviour.Properties properties) {
        super(UniformInt.of(1, 3), properties);
    }
}
