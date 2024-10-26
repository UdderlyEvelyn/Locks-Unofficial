package melonslise.locks.mixin.swapLock;

import com.mojang.datafixers.util.Pair;
import melonslise.locks.Locks;
import melonslise.locks.common.capability.ILockableHandler;
import melonslise.locks.common.config.LocksConfig;
import melonslise.locks.common.init.LocksCapabilities;
import melonslise.locks.common.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Mixin(StructureTemplate.class)
public class StructureTemplateMixin {
    private final List<LockableInfo> lockableInfos = new ArrayList<>();

    @Inject(at = @At("HEAD"), method = "fillFromWorld")
    private void fillFromWorld(Level world, BlockPos start, Vec3i size, boolean takeEntities, @Nullable Block toIgnore, CallbackInfo ci) {
        if (size.getX() >= 1 && size.getY() >= 1 && size.getZ() >= 1) {
            this.lockableInfos.clear();
            ILockableHandler handler = world.getCapability(LocksCapabilities.LOCKABLE_HANDLER).orElse(null);
            Cuboid6i bb = new Cuboid6i(start, start.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1));
            handler.getLoaded().values().stream()
                    .filter(lkb -> lkb.bb.intersects(bb))
                    .forEach(lkb ->
                    {
                        Cuboid6i newBB = bb.intersection(lkb.bb).offset(-start.getX(), -start.getY(), -start.getZ());
                        this.lockableInfos.add(new LockableInfo(newBB, lkb.lock, lkb.tr, lkb.stack, lkb.id));
                    });
        }
    }

    // Second return
    @Inject(at = @At(value = "RETURN", ordinal = 1), method = "placeInWorld")
    private void placeInWorld(ServerLevelAccessor world, BlockPos start, BlockPos size, StructurePlaceSettings settings, RandomSource rng, int i, CallbackInfoReturnable<Boolean> cir) {
        Level level;
        try {
            level = world.getLevel();
        } catch (Exception e) {
            Locks.LOGGER.warn(world + "#getLevel threw an error! Skipping lockable placement for this template ");
            return;
        }
        ILockableHandler handler = level.getCapability(LocksCapabilities.LOCKABLE_HANDLER).orElse(null);
        for (LockableInfo lkb : this.lockableInfos) {
            BlockPos pos1 = LocksUtil.transform(lkb.bb.x1, lkb.bb.y1, lkb.bb.z1, settings);
            BlockPos pos2 = LocksUtil.transform(lkb.bb.x2, lkb.bb.y2, lkb.bb.z2, settings);
            Cuboid6i bb = new Cuboid6i(pos1.getX() + start.getX(), pos1.getY() + start.getY(), pos1.getZ() + start.getZ(), pos2.getX() + start.getX(), pos2.getY() + start.getY(), pos2.getZ() + start.getZ());
            ItemStack stack = LocksConfig.RANDOMIZE_LOADED_LOCKS.get() ? LocksConfig.getRandomLock(rng) : lkb.stack;
            Lock lock = LocksConfig.RANDOMIZE_LOADED_LOCKS.get() ? Lock.from(stack) : lkb.lock;
            Transform tr = Transform.fromDirectionAndFace(settings.getRotation().rotate(settings.getMirror().getRotation(lkb.tr.dir).rotate(lkb.tr.dir)), lkb.tr.face, Direction.NORTH);
            handler.add(new Lockable(bb, lock, tr, stack, level));
        }
    }

    @Unique
    private static final String KEY_LOCKABLES = "Lockables";

    @Inject(at = @At("HEAD"), method = "save")
    private void save(CompoundTag nbt, CallbackInfoReturnable<CompoundTag> cir) {
        ListTag list = new ListTag();
        for (LockableInfo lkb : this.lockableInfos)
            list.add(LockableInfo.toNbt(lkb));
        nbt.put(KEY_LOCKABLES, list);
    }

    @Inject(at = @At("HEAD"), method = "load")
    private void read(HolderGetter<Block> pBlockGetter, CompoundTag nbt, CallbackInfo ci) {
        this.lockableInfos.clear();
        ListTag list = nbt.getList(KEY_LOCKABLES, Tag.TAG_COMPOUND);
        for (int a = 0, b = list.size(); a < b; ++a)
            this.lockableInfos.add(LockableInfo.fromNbt(list.getCompound(a)));
    }
    @Inject(method = "placeInWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z", ordinal = 1, shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILSOFT)
    public void lockBlock(ServerLevelAccessor levelAccessor, BlockPos pOffset, BlockPos pPos,
                          StructurePlaceSettings pSettings, RandomSource pRandom, int pFlags,
                          CallbackInfoReturnable<Boolean> cir,
                          List<StructureTemplate.StructureBlockInfo> list, BoundingBox boundingbox, List<BlockPos> list1, List<BlockPos> list2, List<Pair<BlockPos, CompoundTag>> list3,
                          int i, int j, int k, int l, int i1, int j1,
                          Iterator<BlockPos> var18, StructureTemplate.StructureBlockInfo structuretemplate$structureblockinfo,
                          BlockPos blockPos, FluidState fluidstate, BlockState blockstate
    ) {
        if (levelAccessor.hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)){
            Block block = levelAccessor.getBlockState(blockPos).getBlock();
            if (LocksConfig.canGen(pRandom, block)) {
                LocksUtil.lockWhenGen(levelAccessor, blockPos, RandomSource.create());
            }
        }
    }

    @Inject(method = "placeInWorld", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/ServerLevelAccessor;blockUpdated(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/Block;)V", shift = At.Shift.AFTER), locals = LocalCapture.CAPTURE_FAILSOFT)
    public void lockShape(ServerLevelAccessor levelAccessor, BlockPos pOffset, BlockPos pPos,
                          StructurePlaceSettings pSettings, RandomSource pRandom, int pFlags,
                          CallbackInfoReturnable<Boolean> cir,
                          List<StructureTemplate.StructureBlockInfo> list, BoundingBox boundingbox, List<BlockPos> list1, List<BlockPos> list2, List<Pair<BlockPos, CompoundTag>> list3,
                          int i, int j, int k, int l, int i1, int j1,
                          boolean flag,
                          Direction[] adirection,
                          Iterator<BlockPos> var20, Pair<BlockPos, CompoundTag> pair,
                          BlockPos blockPos, BlockState blockstate2, BlockState blockstate3
    ) {
        if (levelAccessor.hasChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4)){
            Block block = levelAccessor.getBlockState(blockPos).getBlock();
            if (LocksConfig.canGen(pRandom, block)) {
                LocksUtil.lockWhenGen(levelAccessor, blockPos, pRandom);
            }
        }
    }
}