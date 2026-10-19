package lol.pyr.znpcsplus.interaction;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import lol.pyr.znpcsplus.api.event.NpcInteractEvent;
import lol.pyr.znpcsplus.api.interaction.InteractionAction;
import lol.pyr.znpcsplus.api.interaction.InteractionType;
import lol.pyr.znpcsplus.npc.NpcEntryImpl;
import lol.pyr.znpcsplus.npc.NpcImpl;
import lol.pyr.znpcsplus.npc.NpcRegistryImpl;
import lol.pyr.znpcsplus.npc.NpcTypeRegistryImpl;
import lol.pyr.znpcsplus.scheduling.TaskScheduler;
import lol.pyr.znpcsplus.user.User;
import lol.pyr.znpcsplus.user.UserManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Collections;

public class InteractionPacketListener implements PacketListener {
    private final UserManager userManager;
    private final NpcRegistryImpl npcRegistry;
    private final NpcTypeRegistryImpl typeRegistry;
    private final TaskScheduler scheduler;

    public InteractionPacketListener(UserManager userManager, NpcRegistryImpl npcRegistry, NpcTypeRegistryImpl typeRegistry,  TaskScheduler scheduler) {
        this.userManager = userManager;
        this.npcRegistry = npcRegistry;
        this.typeRegistry = typeRegistry;
        this.scheduler = scheduler;
    }

    /** TEMPORARY DEBUG BUILD - logs which early return swallows an NPC click. Not for production. */
    private static void dbg(String msg) {
        Bukkit.getLogger().info("[ZNPC-DEBUG] " + msg);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) return;
        dbg("INTERACT_ENTITY received");

        Player player = (Player) event.getPlayer();
        if (player == null) {
            dbg("  -> DROPPED: event.getPlayer() was null");
            return;
        }
        dbg("  player = " + player.getName());

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        dbg("  entityId = " + packet.getEntityId() + ", action = " + packet.getAction());

        NpcEntryImpl entry = npcRegistry.getByEntityId(packet.getEntityId());
        if (entry == null) {
            dbg("  -> DROPPED: no NPC registered with entity id " + packet.getEntityId());
            return;
        }
        if (!entry.isProcessed()) {
            dbg("  -> DROPPED: NPC " + entry.getId() + " is not processed");
            return;
        }
        dbg("  matched NPC id = " + entry.getId());
        NpcImpl npc = entry.getNpc();

        if ((packet.getAction().equals(WrapperPlayClientInteractEntity.InteractAction.INTERACT)
                || packet.getAction().equals(WrapperPlayClientInteractEntity.InteractAction.INTERACT_AT))
                && npc.getType().equals(typeRegistry.getByName("allay"))) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityEquipment(packet.getEntityId(), Collections.singletonList(
                            new Equipment(EquipmentSlot.MAIN_HAND, ItemStack.EMPTY))));
            player.updateInventory();
        }

        InteractionType type = wrapClickType(packet.getAction());
        dbg("  click type = " + type);

        User user = userManager.get(player);
        if (!user.canInteract()) {
            dbg("  -> DROPPED: canInteract() false (100ms debounce)");
            return;
        }

        NpcInteractEvent interactEvent = new NpcInteractEvent(player, entry, type);
        Bukkit.getPluginManager().callEvent(interactEvent);
        if (interactEvent.isCancelled()) {
            dbg("  -> DROPPED: NpcInteractEvent was cancelled by another plugin");
            return;
        }

        dbg("  NPC has " + npc.getActions().size() + " configured action(s)");
        int ran = 0;
        for (InteractionAction action : npc.getActions()) {
            if (action.getInteractionType() != InteractionType.ANY_CLICK && action.getInteractionType() != type) {
                dbg("    skip: action type " + action.getInteractionType() + " != " + type);
                continue;
            }
            if (action.getCooldown() > 0 && !user.actionCooldownCheck(action)) {
                dbg("    skip: action on cooldown");
                continue;
            }
            dbg("    RUNNING action " + action.getClass().getSimpleName() + " (delay " + action.getDelay() + ")");
            scheduler.runLaterAsync(() -> {
                try {
                    action.run(player);
                    dbg("    action completed: " + action.getClass().getSimpleName());
                } catch (Throwable t) {
                    dbg("    action THREW: " + t);
                    t.printStackTrace();
                }
            }, action.getDelay());
            ran++;
        }
        dbg("  dispatched " + ran + " action(s)");
    }

    private InteractionType wrapClickType(WrapperPlayClientInteractEntity.InteractAction action) {
        switch (action) {
            case ATTACK:
                return InteractionType.LEFT_CLICK;
            case INTERACT:
            case INTERACT_AT:
                return InteractionType.RIGHT_CLICK;
        }
        throw new IllegalStateException();
    }
}
