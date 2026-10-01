package fr.lkdm.homelink.tasks.block;

import fr.lkdm.homecore.api.DashboardAPI;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.board.TaskBoard;
import fr.lkdm.homelink.tasks.registry.TaskRegistries;
import fr.lkdm.homelink.tasks.server.TaskSavedData;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The persistent identity of one physical screen.
 *
 * <p>The screen remembers which board it shows, who placed it and which HomeNetwork it
 * is attached to. It does not store the board: boards live in the mod's saved data, so
 * breaking this block loses nothing but the screen itself.</p>
 *
 * <p>"The same server" here means the same HomeNetwork, not merely the same Minecraft
 * server. Reading stock through this screen requires the board, the screen and the
 * providers to agree on one network identity.</p>
 */
public final class TaskDisplayBlockEntity extends BlockEntity {
    /** Longest custom name accepted, matching HomeCore's rename limit. */
    public static final int MAX_NAME = fr.lkdm.homecore.api.device.Renamable.MAX_LENGTH;

    private UUID id = UUID.randomUUID();
    private UUID owner;
    private UUID networkId;
    private UUID selectedBoard;
    private String customName = "";
    private TaskDisplayDevice device;

    /** Creates the block entity.
     * @param pos block position
     * @param state block state
     */
    public TaskDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(TaskRegistries.TASK_DISPLAY_ENTITY.get(), pos, state);
    }

    /** Returns the screen's stable identity, independent of chunk load cycles.
     * @return persistent device identity
     */
    public UUID id() { return id; }

    /** Returns the player who placed the screen.
     * @return owner identity, or empty when unknown
     */
    public Optional<UUID> owner() { return Optional.ofNullable(owner); }

    /** Records the player who placed the screen.
     * @param player owner identity
     */
    public void setOwner(UUID player) { owner = player; setChanged(); }

    /** Returns the HomeNetwork the screen is attached to.
     * @return network identity, or empty when unbound
     */
    public Optional<UUID> networkId() { return Optional.ofNullable(networkId); }

    /** Records a binding HomeCore has already applied.
     * @param network network identity, or null when detached
     */
    public void setNetworkId(UUID network) { networkId = network; setChanged(); }

    /** Returns the board this screen shows.
     * @return board identity, or empty when none is selected
     */
    public Optional<UUID> selectedBoard() { return Optional.ofNullable(selectedBoard); }

    /** Selects the board this screen shows; several screens may show the same one.
     * @param board board identity, or null to clear
     */
    public void setSelectedBoard(UUID board) { selectedBoard = board; setChanged(); }

    /** Returns the screen's label.
     * @return display name
     */
    public Component displayName() {
        return customName.isBlank() ? Component.translatable("block.homelink_tasks.task_display")
                : Component.literal(customName);
    }

    /** Applies a validated custom name, or restores the default one.
     * @param name trimmed name, empty to restore the default
     */
    public void setCustomName(String name) {
        customName = name == null ? "" : name.strip();
        if (customName.length() > MAX_NAME) customName = customName.substring(0, MAX_NAME);
        setChanged();
    }

    /**
     * Opens the selected board for a player who is allowed to see it.
     *
     * <p>A screen is not an access grant: a player who is not a member of the board is
     * offered the boards they may open instead of that one's contents.</p>
     *
     * @param player authenticated player interacting with the screen
     */
    public void openFor(ServerPlayer player) {
        if (level == null || level.isClientSide) return;
        TaskSavedData data = TaskSavedData.get(player.server);
        Optional<TaskBoard> board = selectedBoard().flatMap(data::board)
                .filter(candidate -> BoardPermissions.canView(candidate, player.getUUID()));
        fr.lkdm.homelink.tasks.network.TaskSubscriptions.of(player.server)
                .openBoard(player, board.map(TaskBoard::id).orElse(null), worldPosition);
    }

    @Override public void onLoad() {
        super.onLoad();
        registerDevice();
    }

    @Override public void setRemoved() {
        unregisterDevice();
        super.setRemoved();
    }

    @Override public void onChunkUnloaded() {
        unregisterDevice();
        super.onChunkUnloaded();
    }

    private void registerDevice() {
        if (!(level instanceof ServerLevel server) || device != null) return;
        device = new TaskDisplayDevice(this);
        try {
            DashboardAPI.devices(server.getServer()).register(device);
        } catch (IllegalArgumentException duplicate) {
            // Another adapter already owns this identity; keep the block usable without a device.
            device = null;
        }
    }

    private void unregisterDevice() {
        if (!(level instanceof ServerLevel server) || device == null) return;
        DashboardAPI.devices(server.getServer()).unregister(id);
        device = null;
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putUUID("Id", id);
        if (owner != null) tag.putUUID("Owner", owner);
        if (networkId != null) tag.putUUID("Network", networkId);
        if (selectedBoard != null) tag.putUUID("Board", selectedBoard);
        if (!customName.isBlank()) tag.putString("CustomName", customName);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.hasUUID("Id")) id = tag.getUUID("Id");
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        networkId = tag.hasUUID("Network") ? tag.getUUID("Network") : null;
        selectedBoard = tag.hasUUID("Board") ? tag.getUUID("Board") : null;
        customName = tag.getString("CustomName");
    }
}
