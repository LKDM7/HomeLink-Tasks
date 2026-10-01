package fr.lkdm.homelink.tasks.client;

import fr.lkdm.homelink.tasks.network.CardView;
import fr.lkdm.homelink.tasks.network.MemberPackets;
import fr.lkdm.homelink.tasks.network.TaskPackets;
import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** Editing is split into labelled sections; advanced controls never crowd the task. */
public final class CardActionsScreen extends TaskScreen {
    private final UUID board, card;
    private int section, member, relation;
    private EditBox name, description;
    private String draftName, draftDescription;
    public CardActionsScreen(UUID board, UUID card) {
        super(Component.translatable("screen.homelink_tasks.edit_card")); this.board = board; this.card = card;
        PacketDistributor.sendToServer(new MemberPackets.Request(board));
    }
    private CardView card() { return ClientTaskState.card(card).orElse(null); }
    private int left() { return (width - wide()) / 2; }
    private int wide() { return Math.min(380, width - 32); }
    private java.util.List<UUID> members() { return ClientTaskState.board().map(b -> b.members().keySet().stream().sorted().toList()).orElse(java.util.List.of()); }
    private java.util.List<CardView> relations() { return ClientTaskState.board().map(b -> b.cards().stream().filter(v -> !v.id().equals(card) && !v.archived()).toList()).orElse(java.util.List.of()); }
    @Override protected void initContent() {
        keepDraft();
        var view = card(); if (view == null) return;
        int x = left(), w = wide(), third = (w - 8) / 3, half = (w - 6) / 2;
        boolean editor = ClientTaskState.board().map(b -> b.viewerRole().atLeast(fr.lkdm.homelink.tasks.board.BoardRole.EDITOR)).orElse(false);
        String[] tabs = {"details", "team", "advanced"};
        for (int i = 0; i < tabs.length; i++) { int tab = i; addRenderableWidget(TaskButton.tab(Component.translatable("screen.homelink_tasks." + tabs[i]), ignored -> { keepDraft(); section = tab; dataChanged(); }, section == i).bounds(x + i * (third + 4), 51, third, 20).build()); }
        if (section == 0) {
            name = new EditBox(font, x, 91, w, 20, Component.translatable("screen.homelink_tasks.title"));
            name.setMaxLength(80); name.setValue(draftName == null ? view.title() : draftName); name.setEditable(editor); addRenderableWidget(name);
            description = new EditBox(font, x, 132, w, 20, Component.translatable("screen.homelink_tasks.description"));
            description.setMaxLength(2000); description.setValue(draftDescription == null ? view.description() : draftDescription); description.setEditable(editor); addRenderableWidget(description);
            var priority = button("priority", x, 166, w, () -> { var values = fr.lkdm.homelink.tasks.task.TaskPriority.values(); TaskClientNetwork.priority(board, card, values[(card().priority().ordinal() + 1) % values.length]); });
            priority.setMessage(Component.translatable("screen.homelink_tasks.priority_value", Component.translatable("priority.homelink_tasks." + view.priority().name().toLowerCase(java.util.Locale.ROOT)))); priority.active = editor;
            var save = addRenderableWidget(TaskButton.primary(Component.translatable("screen.homelink_tasks.save"), ignored -> save()).bounds(x + half + 6, height - 26, half, 20).build());
            save.active = editor && !name.getValue().isBlank();
            name.setResponder(value -> save.active = editor && !value.isBlank() && savePending == null);
        } else if (section == 1) {
            button("claim", x, 84, w, () -> change(TaskPackets.CardCommand.CLAIM, "", false)).active = ClientCardPermissions.canClaim(view);
            button("previous", x, 121, 28, () -> { member--; dataChanged(); }).active = members().size() > 1;
            button("next", x + w - 28, 121, 28, () -> { member++; dataChanged(); }).active = members().size() > 1;
            button("assign", x, 158, half, () -> assign(TaskPackets.CardCommand.ASSIGN)).active = editor && !members().isEmpty();
            button("unassign", x + half + 6, 158, half, () -> assign(TaskPackets.CardCommand.UNASSIGN)).active = editor && !members().isEmpty();
        } else {
            button("relationships", x, 84, w, () -> { section = 3; dataChanged(); });
            if (view.objective().isPresent()) button("craft_settings", x, 110, w, () -> minecraft.setScreen(new ObjectiveSettingsScreen(board, card)));
            button(view.archived() ? "restore_task" : "archive_card", x, 146, w, () -> { change(TaskPackets.CardCommand.ARCHIVE, "", !card().archived()); onClose(); }).active = editor;
            button("delete_card", x, 172, w, () -> minecraft.setScreen(new ConfirmScreen(accepted -> { minecraft.setScreen(this); if (accepted) { change(TaskPackets.CardCommand.DELETE, "", true); BoardScreen.openOrRefresh(); } }, Component.translatable("screen.homelink_tasks.delete_card"), Component.translatable("screen.homelink_tasks.confirm_action")))).active = editor;
            if (section == 3) {
                clearWidgets();
                button("previous", x, 78, 28, () -> { relation--; dataChanged(); }).active = relations().size() > 1;
                button("next", x + w - 28, 78, 28, () -> { relation++; dataChanged(); }).active = relations().size() > 1;
                button("parent_link", x, 112, half, () -> link(TaskPackets.CardCommand.SET_PARENT)).active = editor && !relations().isEmpty();
                button("dependency_link", x + half + 6, 112, half, () -> link(TaskPackets.CardCommand.ADD_DEPENDENCY)).active = editor && !relations().isEmpty();
                button("unlink_parent", x, 142, half, () -> TaskClientNetwork.link(TaskPackets.CardCommand.SET_PARENT, board, card, null)).active = editor;
                button("unlink_dependency", x + half + 6, 142, half, () -> link(TaskPackets.CardCommand.REMOVE_DEPENDENCY)).active = editor && !relations().isEmpty();
            }
        }
        button("back", x, height - 26, section == 0 ? half : w, this::onClose);
    }
    // Serialize the two edits: the second uses the revision returned for the first.
    private String savePending;
    @Override public void requestFailed() { savePending = null; dataChanged(); }
    private void save() {
        keepDraft(); draftName = draftName.strip(); if (draftName.isEmpty() || savePending != null) return;
        if (card().title().equals(draftName)) { change(TaskPackets.CardCommand.DESCRIBE, draftDescription, false); onClose(); }
        else { savePending = draftDescription; change(TaskPackets.CardCommand.RENAME, draftName, false); }
    }
    private void keepDraft() { if (name != null) draftName = name.getValue(); if (description != null) draftDescription = description.getValue(); }
    private void change(TaskPackets.CardCommand command, String text, boolean flag) { TaskClientNetwork.card(command, board, card, text, flag); }
    private void assign(TaskPackets.CardCommand command) { var list = members(); if (!list.isEmpty()) TaskClientNetwork.assignment(command, board, card, list.get(Math.floorMod(member, list.size()))); }
    private void link(TaskPackets.CardCommand command) { var list = relations(); if (!list.isEmpty()) TaskClientNetwork.link(command, board, card, list.get(Math.floorMod(relation, list.size())).id()); }
    private net.minecraft.client.gui.components.Button button(String key, int x, int y, int w, Runnable run) { return addRenderableWidget(TaskButton.builder(Component.translatable("screen.homelink_tasks." + key), ignored -> run.run()).bounds(x, y, w, 20).build()); }
    @Override public void dataChanged() {
        if (savePending != null && card() != null && card().title().equals(draftName)) { String text = savePending; savePending = null; change(TaskPackets.CardCommand.DESCRIBE, text, false); minecraft.setScreen(new CardDetailScreen(board, card)); return; }
        keepDraft(); clearWidgets(); initContent();
    }
    @Override public void onClose() { if (section == 3) { section = 2; dataChanged(); } else minecraft.setScreen(new CardDetailScreen(board, card)); }
    @Override public void renderContent(GuiGraphics g, int mx, int my, float delta) {
        renderBackground(g, mx, my, delta); int x = left(), w = wide();
        g.drawString(font, Component.translatable(section == 3 ? "screen.homelink_tasks.relationships" : "screen.homelink_tasks.edit_card"), x, 33, TaskTheme.TEXT, false);
        if (section == 0) { g.drawString(font, Component.translatable("screen.homelink_tasks.title"), x, 79, TaskTheme.TEXT_MUTED, false); g.drawString(font, Component.translatable("screen.homelink_tasks.description"), x, 120, TaskTheme.TEXT_MUTED, false); }
        if (section == 1 && !members().isEmpty()) g.drawString(font, TaskTheme.clip(font, ClientTaskState.playerName(members().get(Math.floorMod(member, members().size()))), w - 76), x + 36, 127, TaskTheme.TEXT, false);
        if (section == 3 && !relations().isEmpty()) g.drawString(font, TaskTheme.clip(font, relations().get(Math.floorMod(relation, relations().size())).title(), w - 76), x + 36, 84, TaskTheme.TEXT, false);
        super.renderContent(g, mx, my, delta);
    }
}
