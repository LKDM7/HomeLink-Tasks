package fr.lkdm.homelink.tasks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlock;
import fr.lkdm.homelink.tasks.block.TaskDisplayBlockEntity;
import fr.lkdm.homelink.tasks.network.DisplayPackets;
import fr.lkdm.homelink.tasks.task.TaskStatus;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.network.chat.Component;

/** Draws only the local observer's authorized summary, never board data from chunk NBT. */
public final class TaskDisplayRenderer implements BlockEntityRenderer<TaskDisplayBlockEntity> {
    private final Font font;
    public TaskDisplayRenderer(BlockEntityRendererProvider.Context context) { font = context.getFont(); }

    @Override public int getViewDistance() { return 16; }
    @Override public boolean shouldRenderOffScreen(TaskDisplayBlockEntity screen) { return true; }

    @Override public boolean shouldRender(TaskDisplayBlockEntity screen, net.minecraft.world.phys.Vec3 camera) {
        if (!BlockEntityRenderer.super.shouldRender(screen, camera)) return false;
        var facing = screen.getBlockState().getValue(TaskDisplayBlock.FACING);
        return camera.subtract(net.minecraft.world.phys.Vec3.atCenterOf(screen.getBlockPos()))
                .dot(net.minecraft.world.phys.Vec3.atLowerCornerOf(facing.getNormal())) > -0.3048;
    }

    @Override public void render(TaskDisplayBlockEntity screen, float partialTick, PoseStack pose,
                                 MultiBufferSource buffers, int light, int overlay) {
        if (!TaskClientConfig.DISPLAY_VISIBLE.get()) return;
        if (!(screen.getBlockState().getBlock() instanceof TaskDisplayBlock block)) return;
        DisplayClientState.observe(screen.getBlockPos());
        var size = screen.getBlockState().getValue(TaskDisplayBlock.SIZE);
        int width = (int)((size.width() - 0.1F) * 160);
        int height = (int)((size.height() - 0.36875F) * 160);
        pose.pushPose();
        pose.translate(0.5, 0.5, 0.5);
        pose.mulPose(Axis.YP.rotationDegrees(-screen.getBlockState().getValue(TaskDisplayBlock.FACING).toYRot()));
        pose.translate(-0.45, size.height() - 0.675, -0.3048);
        pose.scale(1 / 160F, -1 / 160F, 1 / 160F);
        fill(pose, buffers, 0, 0, width, height, TaskTheme.SURFACE, 0);
        fill(pose, buffers, 0, 0, width, 19, TaskTheme.PANEL);
        fill(pose, buffers, 0, 19, width, 20, TaskTheme.COPPER);
        text(pose, buffers, "HOMELINK / TASKS", 6, 6, TaskTheme.COPPER);
        var view = DisplayClientState.view(screen.getBlockPos()).orElse(null);
        if (view == null) {
            String key = DisplayClientState.state(screen.getBlockPos()).name().toLowerCase(java.util.Locale.ROOT);
            text(pose, buffers, TaskTheme.clip(font, Component.translatable("display.homelink_tasks." + key).getString(), width - 12), 6, 30, TaskTheme.TEXT);
            text(pose, buffers, TaskTheme.clip(font, Component.translatable("display.homelink_tasks.open").getString(), width - 12), 6, 44, TaskTheme.TEXT_MUTED);
        } else {
            text(pose, buffers, TaskTheme.clip(font, view.title(), width - 12), 6, 26, TaskTheme.TEXT);
            text(pose, buffers, view.done() + " / " + view.total(), 6, 40, TaskTheme.TEXT_MUTED);
            int availableWidth = width - 46;
            fill(pose, buffers, 40, 42, width - 6, 45, TaskTheme.PANEL);
            if (view.total() > 0) fill(pose, buffers, 40, 42, 40 + availableWidth * view.done() / view.total(), 45, TaskTheme.COPPER, 0.2F);
            int rows = Math.min(view.cards().size(), (height - 66) / 21);
            for (int index = 0; index < rows; index++) {
                DisplayPackets.Card card = view.cards().get(index);
                int y = 55 + index * 21;
                fill(pose, buffers, 5, y - 2, width - 5, y + 17, TaskTheme.ANTHRACITE);
                text(pose, buffers, card.status() == TaskStatus.DONE ? "+" : card.status() == TaskStatus.IN_PROGRESS ? ">" : "-",
                        8, y, TaskTheme.COPPER);
                text(pose, buffers, TaskTheme.clip(font, card.title(), width - 24), 18, y, TaskTheme.TEXT);
                String status = Component.translatable("column.homelink_tasks." + card.status().name().toLowerCase(java.util.Locale.ROOT)).getString();
                text(pose, buffers, card.target() == 0 ? status : card.completed() + " / " + card.target(), 18, y + 10, TaskTheme.TEXT_MUTED);
            }
            if (rows == 0) text(pose, buffers, TaskTheme.clip(font, Component.translatable("screen.homelink_tasks.empty_column").getString(), width - 12), 6, 58, TaskTheme.TEXT_MUTED);
            text(pose, buffers, Component.translatable("display.homelink_tasks.live").getString(), 6, height - 10, TaskTheme.COPPER);
        }
        pose.popPose();
    }

    private void text(PoseStack pose, MultiBufferSource buffers, String value, int x, int y, int color) {
        // After the facing rotation the viewer is on local +Z.
        pose.pushPose();
        pose.translate(0, 0, 0.4F);
        font.drawInBatch(value, x, y, color, false, pose.last().pose(), buffers, Font.DisplayMode.NORMAL, 0, 0xF000F0);
        pose.popPose();
    }

    private static void fill(PoseStack pose, MultiBufferSource buffers, int left, int top, int right, int bottom, int color) {
        fill(pose, buffers, left, top, right, bottom, color, 0.1F);
    }

    private static void fill(PoseStack pose, MultiBufferSource buffers, int left, int top, int right, int bottom, int color, float depth) {
        // Use a world-space position/color pipeline, not the GUI shader/batch.
        var vertices = buffers.getBuffer(RenderType.debugQuads());
        var matrix = pose.last().pose();
        vertices.addVertex(matrix, left, top, depth).setColor(color);
        vertices.addVertex(matrix, left, bottom, depth).setColor(color);
        vertices.addVertex(matrix, right, bottom, depth).setColor(color);
        vertices.addVertex(matrix, right, top, depth).setColor(color);
    }
}
