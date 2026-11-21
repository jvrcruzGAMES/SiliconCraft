package games.jvrcruz.siliconcraft.compat.jade;

import games.jvrcruz.siliconcraft.Siliconcraft;
import games.jvrcruz.siliconcraft.block.BaseMachineBlock;
import games.jvrcruz.siliconcraft.block.ComputerBlock;
import games.jvrcruz.siliconcraft.block.SpeakerBlock;
import games.jvrcruz.siliconcraft.blockentity.BaseMachineBlockEntity;
import games.jvrcruz.siliconcraft.blockentity.ComputerBlockEntity;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;

@WailaPlugin(Siliconcraft.MODID)
public class SiliconCraftJadePlugin implements IWailaPlugin {

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(ComputerBlockProvider.INSTANCE, ComputerBlockEntity.class);
        registration.registerBlockDataProvider(MachineBlockProvider.INSTANCE, BaseMachineBlockEntity.class);
        registration.registerBlockDataProvider(SpeakerBlockProvider.INSTANCE, SpeakerBlock.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(ComputerBlockProvider.INSTANCE, ComputerBlock.class);
        registration.registerBlockComponent(MachineBlockProvider.INSTANCE, BaseMachineBlock.class);
        registration.registerBlockComponent(SpeakerBlockProvider.INSTANCE, SpeakerBlock.class);
    }
}
