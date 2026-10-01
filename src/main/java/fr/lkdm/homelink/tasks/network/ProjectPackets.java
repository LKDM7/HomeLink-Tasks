package fr.lkdm.homelink.tasks.network;

import fr.lkdm.homelink.tasks.HomeLinkTasks;
import fr.lkdm.homelink.tasks.board.BoardPermissions;
import fr.lkdm.homelink.tasks.recipe.ProjectMaterialPlan;
import fr.lkdm.homelink.tasks.recipe.RecipeResolver;
import fr.lkdm.homelink.tasks.server.TaskManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ProjectPackets {
    private ProjectPackets() { }
    public record Need(UUID card, ResourceLocation recipe, List<ItemStack> alternatives, int quantity,
                       int fromInventory, int fromStock, fr.lkdm.homelink.tasks.stock.AvailabilityState state) {
        public Need { alternatives = alternatives.stream().limit(16).map(ItemStack::copy).toList(); }
    }
    public record Request(UUID board) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HomeLinkTasks.id("project_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeUUID(value.board()), buffer -> new Request(buffer.readUUID()));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Snapshot(UUID board, List<Need> needs, boolean incomplete, boolean storageConfigured,
                           fr.lkdm.homecore.api.stock.StockAccess access) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(HomeLinkTasks.id("project_plan"));
        public Snapshot {
            needs = List.copyOf(needs);
            if (needs.size() > ProjectMaterialPlan.MAX_NEEDS) throw new IllegalArgumentException("Plan limit");
        }
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC = StreamCodec.of((buffer, value) -> {
            buffer.writeUUID(value.board()); buffer.writeBoolean(value.incomplete());
            buffer.writeBoolean(value.storageConfigured()); buffer.writeEnum(value.access()); buffer.writeVarInt(value.needs().size());
            for (Need need : value.needs()) {
                buffer.writeUUID(need.card()); buffer.writeResourceLocation(need.recipe()); buffer.writeVarInt(need.quantity());
                buffer.writeVarInt(need.fromInventory()); buffer.writeVarInt(need.fromStock()); buffer.writeEnum(need.state());
                buffer.writeVarInt(need.alternatives().size());
                for (ItemStack stack : need.alternatives()) ItemStack.STREAM_CODEC.encode(buffer, stack);
            }
        }, buffer -> {
            UUID board = buffer.readUUID(); boolean incomplete = buffer.readBoolean();
            boolean configured = buffer.readBoolean(); var access = buffer.readEnum(fr.lkdm.homecore.api.stock.StockAccess.class);
            int count = bounded(buffer.readVarInt(), ProjectMaterialPlan.MAX_NEEDS);
            List<Need> needs = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                UUID card = buffer.readUUID(); ResourceLocation recipe = buffer.readResourceLocation();
                int quantity = bounded(buffer.readVarInt(), Integer.MAX_VALUE);
                int inventory = bounded(buffer.readVarInt(), quantity), stock = bounded(buffer.readVarInt(), quantity);
                var state = buffer.readEnum(fr.lkdm.homelink.tasks.stock.AvailabilityState.class);
                int choices = bounded(buffer.readVarInt(), 16); List<ItemStack> alternatives = new ArrayList<>(choices);
                for (int choice = 0; choice < choices; choice++) alternatives.add(ItemStack.STREAM_CODEC.decode(buffer));
                needs.add(new Need(card, recipe, alternatives, quantity, inventory, stock, state));
            }
            return new Snapshot(board, needs, incomplete, configured, access);
        });
        @Override public Type<Snapshot> type() { return TYPE; }
    }
    private static int bounded(int value, int max) {
        if (value < 0 || value > max) throw new IllegalArgumentException("Payload bound"); return value;
    }
    public static void request(Request request, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var manager = TaskManager.get(player.server);
            if (!manager.budget().claim(player.getUUID(), player.server.overworld().getGameTime())) return;
            var board = manager.data().board(request.board()).orElse(null);
            if (board == null || !BoardPermissions.canView(board, player.getUUID())) return;
            if (!manager.calculations().claim(player.server.getTickCount(),
                    fr.lkdm.homelink.tasks.server.CalculationBudget.PROJECT_COST)) return;
            var plan = ProjectMaterialPlan.build(board, id -> RecipeResolver.describe(player.server, id));
            var ingredients = plan.needs().stream().map(ProjectMaterialPlan.Need::ingredient).toList();
            var stock = manager.stockFor(player, board, ingredients);
            var allocation = fr.lkdm.homelink.tasks.stock.IngredientAvailabilityPlanner.planDemand(ingredients,
                    plan.needs().stream().mapToInt(ProjectMaterialPlan.Need::quantity).toArray(),
                    fr.lkdm.homelink.tasks.stock.PlayerInventoryView.countable(player), stock);
            List<Need> needs = new ArrayList<>();
            boolean incomplete = plan.incomplete() || allocation.ingredients().size() != plan.needs().size();
            for (int index = 0; index < plan.needs().size(); index++) {
                var need = plan.needs().get(index);
                var part = index < allocation.ingredients().size() ? allocation.ingredients().get(index) : null;
                var choices = List.of(need.ingredient().getItems());
                incomplete |= choices.size() > 16;
                needs.add(new Need(need.card(), need.recipe(), choices, need.quantity(), part == null ? 0 : part.fromInventory(),
                        part == null ? 0 : part.fromStock(), part == null ? fr.lkdm.homelink.tasks.stock.AvailabilityState.UNVERIFIED : part.state()));
            }
            TaskTransport.send(player, new Snapshot(board.id(), needs, incomplete, stock.configured(), stock.access()));
        });
    }
}
