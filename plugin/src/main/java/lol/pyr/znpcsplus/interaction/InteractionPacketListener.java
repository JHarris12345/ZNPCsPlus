package lol.pyr.znpcsplus.interaction;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
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

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        boolean isInteract = event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY;

        // Minecraft 26.x split attacking an entity out of the interact packet into its own
        // ServerboundAttackPacket, so a LEFT click no longer arrives as INTERACT_ENTITY with the ATTACK
        // action - it arrives as a separate ATTACK packet that this listener used to ignore outright.
        // That is why right clicks worked and left clicks did nothing at all, even on ANY_CLICK actions.
        //
        // Both are handled so one jar covers old and new servers: on an older server the ATTACK packet
        // never arrives and left clicks still come through INTERACT_ENTITY as before.
        boolean isAttack = event.getPacketType() == PacketType.Play.Client.ATTACK;
        if (!isInteract && !isAttack) return;

        Player player = (Player) event.getPlayer();
        if (player == null) return;

        int entityId;
        InteractionType type;

        if (isInteract) {
            WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
            entityId = packet.getEntityId();
            type = wrapClickType(packet.getAction());
        } else {
            entityId = new WrapperPlayClientAttack(event).getEntityId();
            type = InteractionType.LEFT_CLICK;
        }

        NpcEntryImpl entry = npcRegistry.getByEntityId(entityId);
        if (entry == null || !entry.isProcessed()) return;
        NpcImpl npc = entry.getNpc();

        // Right click only, so it stays off the attack path
        if (type == InteractionType.RIGHT_CLICK && npc.getType().equals(typeRegistry.getByName("allay"))) {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player,
                    new WrapperPlayServerEntityEquipment(entityId, Collections.singletonList(
                            new Equipment(EquipmentSlot.MAIN_HAND, ItemStack.EMPTY))));
            player.updateInventory();
        }

        User user = userManager.get(player);
        if (!user.canInteract()) return;

        NpcInteractEvent interactEvent = new NpcInteractEvent(player, entry, type);
        Bukkit.getPluginManager().callEvent(interactEvent);
        if (interactEvent.isCancelled()) return;

        for (InteractionAction action : npc.getActions()) {
            if (action.getInteractionType() != InteractionType.ANY_CLICK && action.getInteractionType() != type) continue;
            if (action.getCooldown() > 0 && !user.actionCooldownCheck(action)) continue;
            scheduler.runLaterAsync(() -> action.run(player), action.getDelay());
        }
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
