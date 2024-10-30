package melonslise.locks.mixin;

import melonslise.locks.common.capability.ILockableHandler;
import melonslise.locks.common.util.Lockable;
import melonslise.locks.compact.LootrCompactHandler;
import melonslise.locks.common.init.LocksCapabilities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.stream.Collectors;

@Mixin(ServerLevel.class)
public class ServerWorldMixin
{
	@Inject(at = @At("HEAD"), method = "sendBlockUpdated")
	private void sendBlockUpdated(BlockPos pos, BlockState oldState, BlockState newState, int flag, CallbackInfo ci)
	{
		if(oldState.is(newState.getBlock()))
			return;
		ServerLevel world = (ServerLevel) (Object) this;
		ILockableHandler handler = world.getCapability(LocksCapabilities.LOCKABLE_HANDLER).orElse(null);
		if(ModList.get().isLoaded("lootr")){
			LootrCompactHandler.handleBlockUpdate(world, handler, pos, oldState, newState);
		}else {
			if(newState.is(Blocks.CHEST)||newState.is(Blocks.TRAPPED_CHEST)||newState.is(Blocks.BARREL)){
				List<Lockable> collect = handler.getInChunk(pos).values().stream().filter(lkb -> lkb.bb.intersects(pos)).collect(Collectors.toList());
				if(collect.size()>1){
					for(Lockable lkb:collect.subList(1,collect.size())){
						handler.remove(lkb.id);
					}
				}
				return;
			}
			// create buffer list because otherwise we will be deleting elements while iterating (BAD!!)
			handler.getInChunk(pos).values().stream().filter(lkb -> lkb.bb.intersects(pos)).collect(Collectors.toList()).forEach(lkb ->
			{
				world.playSound(null, pos, SoundEvents.IRON_DOOR_OPEN, SoundSource.BLOCKS, 0.8f, 0.8f + world.random.nextFloat() * 0.4f);
				world.addFreshEntity(new ItemEntity(world, pos.getX() + 0.5d, pos.getY() + 0.5d, pos.getZ() + 0.5d, lkb.stack));
				handler.remove(lkb.id);
			});

		}


	}
}