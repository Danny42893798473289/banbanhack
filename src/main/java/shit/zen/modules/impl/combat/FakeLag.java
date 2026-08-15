package shit.zen.modules.impl.combat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import shit.zen.event.EventTarget;
import shit.zen.event.impl.DisconnectEvent;
import shit.zen.event.impl.PacketEvent;
import shit.zen.event.impl.ReceivePacketEvent;
import shit.zen.event.impl.RenderEvent;
import shit.zen.event.impl.TickEvent;
import shit.zen.modules.Category;
import shit.zen.modules.Module;
import shit.zen.settings.impl.BooleanSetting;
import shit.zen.settings.impl.ModeSetting;
import shit.zen.settings.impl.NumberSetting;
import shit.zen.utils.misc.PacketUtil;
import shit.zen.utils.render.RenderUtil;

/**
 * FakeLag — Vape-style delay, Grim-safe release.
 *
 * Grim Simulation flags burst-flushed move packets; that causes setbacks, then
 * BadPacketsN when stale positions fight the teleport accept.
 *
 * Rules for Dynamic on Grim:
 *  - drip at most 1 move packet per tick (never dump the queue)
 *  - discard (don't replay) on setback / velocity
 *  - hard-cap queue size so desync stays within 1–2 ticks
 */
public class FakeLag extends Module {

    public static FakeLag INSTANCE;

    private static final class QueuedPacket {
        final Packet<?> packet;
        final long time;
        final Vec3 pos;

        QueuedPacket(Packet<?> packet, long time, Vec3 pos) {
            this.packet = packet;
            this.time = time;
            this.pos = pos;
        }
    }

    private final ModeSetting mode = new ModeSetting("Mode", "Latency", "Dynamic", "Repel")
            .withDefault("Dynamic");
    private final NumberSetting range = new NumberSetting("Range", 4.0, 1.0, 8.0, 0.1);
    private final NumberSetting delay = new NumberSetting("Delay", 50.0, 0.0, 200.0, 5.0);
    private final NumberSetting recoilTime = new NumberSetting("Recoil Time", 40.0, 0.0, 300.0, 5.0);
    private final NumberSetting maxQueue = new NumberSetting("Max Queue", 2.0, 1.0, 4.0, 1.0);
    private final NumberSetting transmissionOffset = new NumberSetting(
            "Transmission Offset", 0.0, 0.0, 80.0, 1.0,
            () -> this.mode.is("Repel"));
    private final BooleanSetting flushOnAttack = new BooleanSetting("Flush On Attack", true,
            () -> !this.mode.is("Repel"));
    private final BooleanSetting flushOnInteract = new BooleanSetting("Flush On Interact", true);
    private final BooleanSetting flushOnDamage = new BooleanSetting("Flush On Damage", true);
    private final BooleanSetting render = new BooleanSetting("Render", true);

    private final List<QueuedPacket> packetQueue = new ArrayList<>();
    private long lastFlushTime = 0L;
    private long repelUntilMs = 0L;
    private Vec3 serverPos = null;
    private int releasesThisTick = 0;
    private boolean catchUp;

    public FakeLag() {
        super("FakeLag", Category.COMBAT);
        INSTANCE = this;
    }

    @Override
    protected void onEnable() {
        this.discardQueue();
        this.lastFlushTime = System.currentTimeMillis();
        this.repelUntilMs = 0L;
        this.catchUp = false;
        this.serverPos = mc.player != null ? mc.player.position() : null;
    }

    @Override
    protected void onDisable() {
        this.catchUp = true;
        // Don't burst — drip will finish on ticks; force one release so we aren't stuck.
        this.releasesThisTick = 0;
        while (!this.packetQueue.isEmpty() && this.releasesThisTick < 3) {
            this.releaseOne();
        }
        this.discardQueue();
        this.repelUntilMs = 0L;
        this.catchUp = false;
    }

    public void notifyAttack() {
        if (!this.isEnabled() || !this.mode.is("Repel")) {
            return;
        }
        long hold = this.delay.getValue().longValue() + this.transmissionOffset.getValue().longValue();
        long until = System.currentTimeMillis() + Math.min(hold, 120L);
        if (until > this.repelUntilMs) {
            this.repelUntilMs = until;
        }
    }

    @EventTarget
    public void onTick(TickEvent event) {
        this.releasesThisTick = 0;

        if (mc.player == null || mc.level == null) {
            this.discardQueue();
            return;
        }

        boolean lag = this.shouldLag();
        if (!lag && !this.packetQueue.isEmpty()) {
            this.catchUp = true;
        }
        if (lag) {
            this.catchUp = false;
        }

        // Age-based drip (max 1 / tick).
        this.dripAged();

        // Soft catch-up when Dynamic stops — still 1 packet/tick, never dump.
        if (this.catchUp && !this.packetQueue.isEmpty()) {
            this.releaseOne();
            if (this.packetQueue.isEmpty()) {
                this.catchUp = false;
                this.lastFlushTime = System.currentTimeMillis();
            }
        }

        // Hard queue cap for Grim.
        while (this.packetQueue.size() > this.maxQueueSize() && this.releasesThisTick < 1) {
            this.releaseOne();
        }
    }

    @EventTarget
    public void onReceivePacket(ReceivePacketEvent event) {
        if (mc.player == null) {
            return;
        }

        Packet<ClientGamePacketListener> packet = event.getPacket();

        // Setback / teleport: discard stale path so we don't fight Grim's accept.
        // Replaying old positions here is what chains Simulation → BadPacketsN.
        if (packet instanceof ClientboundPlayerPositionPacket) {
            this.discardQueue();
            this.catchUp = false;
            this.repelUntilMs = 0L;
            this.lastFlushTime = System.currentTimeMillis();
            return;
        }

        if (this.packetQueue.isEmpty() || !this.flushOnDamage.getValue()) {
            return;
        }

        if (packet instanceof ClientboundSetEntityMotionPacket motion) {
            if (motion.getId() == mc.player.getId()
                    && (motion.getXa() != 0 || motion.getYa() != 0 || motion.getZa() != 0)) {
                // KB invalidates the held path — discard, don't replay.
                this.discardQueue();
                this.catchUp = false;
                this.lastFlushTime = System.currentTimeMillis();
            }
            return;
        }

        if (packet instanceof ClientboundSetHealthPacket health
                && health.getHealth() < mc.player.getHealth()) {
            this.discardQueue();
            this.catchUp = false;
            this.lastFlushTime = System.currentTimeMillis();
        }
    }

    @EventTarget
    public void onPacket(PacketEvent event) {
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (event.isIncoming()) {
            return;
        }

        Packet<?> packet = event.getPacket();

        if (!(packet instanceof ServerboundMovePlayerPacket move)) {
            if (packet instanceof ServerboundInteractPacket) {
                this.notifyAttack();
                if (this.flushOnAttack.getValue() && !this.mode.is("Repel")) {
                    // Start drip catch-up instead of dumping.
                    this.catchUp = true;
                }
            } else if (this.flushOnInteract.getValue() && this.isInteractPacket(packet)) {
                this.catchUp = true;
            }
            return;
        }

        // Always let pure rotation / ground status through — Grim timer hates silence.
        if (move instanceof ServerboundMovePlayerPacket.Rot
                || move instanceof ServerboundMovePlayerPacket.StatusOnly) {
            return;
        }

        if (!this.shouldLag() || this.catchUp) {
            return;
        }

        if (System.currentTimeMillis() - this.lastFlushTime < this.recoilTime.getValue().longValue()) {
            return;
        }

        Vec3 packetPos = this.extractPos(move);
        if (packetPos == null) {
            packetPos = mc.player.position();
        }
        if (this.serverPos == null) {
            this.serverPos = packetPos;
        }

        if (this.isDynamicMode() && !this.isDynamicAdvantage(this.serverPos, mc.player.position())) {
            this.catchUp = true;
            return;
        }

        // Queue full → drip one then accept, never grow unbounded.
        if (this.packetQueue.size() >= this.maxQueueSize()) {
            this.releaseOne();
        }

        event.setCancelled(true);
        this.packetQueue.add(new QueuedPacket(move, System.currentTimeMillis(), packetPos));
    }

    @EventTarget
    public void onRender(RenderEvent event) {
        if (!this.render.getValue() || mc.player == null || mc.gameRenderer == null) {
            return;
        }
        if (this.packetQueue.isEmpty()) {
            return;
        }

        Vec3 ghost = this.serverPos != null ? this.serverPos : this.firstQueuedPos();
        if (ghost == null) {
            return;
        }

        PoseStack poseStack = event.poseStack();
        poseStack.pushPose();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.translate(ghost.x - camera.x, ghost.y - camera.y, ghost.z - camera.z);
        double half = mc.player.getBbWidth() / 2.0;
        AABB box = new AABB(-half, 0.0, -half, half, mc.player.getBbHeight(), half);
        Color fill = new Color(0, 200, 255, 40);
        Color outline = new Color(0, 220, 255, 180);
        RenderUtil.drawFilledColoredBox(box, poseStack, fill, fill);
        RenderUtil.drawColoredBox(box, poseStack, outline, outline);
        poseStack.popPose();

        List<Vec3> path = this.getQueuedPositions();
        if (path.size() >= 2) {
            this.drawTrail(event.poseStack(), path);
        }
    }

    @EventTarget
    public void onDisconnect(DisconnectEvent event) {
        this.discardQueue();
        this.repelUntilMs = 0L;
        this.catchUp = false;
    }

    private int maxQueueSize() {
        int cap = this.maxQueue.getValue().intValue();
        if (this.isDynamicMode()) {
            return Math.min(cap, 2);
        }
        return Math.min(cap, 4);
    }

    private boolean isDynamicMode() {
        String current = this.mode.getValue();
        return "Dynamic".equals(current) || "Smart".equals(current);
    }

    private boolean shouldLag() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        // Don't lag while hurt-time / mid-kb — Grim prediction is strict here.
        if (mc.player.hurtTime > 0) {
            return false;
        }

        String current = this.mode.getValue();
        if (this.isDynamicMode()) {
            Vec3 held = this.serverPos != null ? this.serverPos : mc.player.position();
            return this.isEnemyNearby() && this.isDynamicAdvantage(held, mc.player.position());
        }
        if ("Constant".equals(current) || "Latency".equals(current)) {
            return true;
        }
        if ("Repel".equals(current)) {
            return System.currentTimeMillis() < this.repelUntilMs;
        }
        return false;
    }

    private boolean isDynamicAdvantage(Vec3 heldPos, Vec3 realPos) {
        if (heldPos == null || realPos == null || mc.player == null || mc.level == null) {
            return false;
        }

        double maxRange = this.range.getValue().doubleValue();
        Player nearest = null;
        double nearestReal = Double.MAX_VALUE;

        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (AntiBots.isBot(player)) {
                continue;
            }
            double d = realPos.distanceTo(player.position());
            if (d <= maxRange && d < nearestReal) {
                nearestReal = d;
                nearest = player;
            }
        }

        if (nearest == null) {
            return false;
        }

        // Only lag when we actually gained meaningful separation (≥ 0.15 blocks).
        double serverDist = heldPos.distanceTo(nearest.position());
        double clientDist = realPos.distanceTo(nearest.position());
        if (serverDist < clientDist + 0.15) {
            return false;
        }

        double half = mc.player.getBbWidth() / 2.0;
        AABB heldBox = new AABB(
                heldPos.x - half, heldPos.y, heldPos.z - half,
                heldPos.x + half, heldPos.y + mc.player.getBbHeight(), heldPos.z + half);
        return !heldBox.intersects(nearest.getBoundingBox().inflate(0.05));
    }

    private boolean isEnemyNearby() {
        if (mc.player == null || mc.level == null) {
            return false;
        }
        double r = this.range.getValue().doubleValue();
        double rSq = r * r;
        for (Player player : mc.level.players()) {
            if (player == mc.player || !player.isAlive() || player.isSpectator()) {
                continue;
            }
            if (AntiBots.isBot(player)) {
                continue;
            }
            if (mc.player.distanceToSqr(player) <= rSq) {
                return true;
            }
        }
        return false;
    }

    private void dripAged() {
        if (this.packetQueue.isEmpty() || mc.getConnection() == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long maxAge = Math.min(this.delay.getValue().longValue(), 100L);
        if (this.mode.is("Repel")) {
            maxAge = Math.min(maxAge + this.transmissionOffset.getValue().longValue(), 120L);
        }
        if (now - this.packetQueue.get(0).time >= maxAge) {
            this.releaseOne();
        }
    }

    private void releaseOne() {
        if (this.packetQueue.isEmpty() || this.releasesThisTick >= 1) {
            return;
        }
        QueuedPacket entry = this.packetQueue.remove(0);
        this.sendQueued(entry.packet);
        this.serverPos = entry.pos;
        this.releasesThisTick++;
        if (this.packetQueue.isEmpty() && mc.player != null) {
            this.serverPos = mc.player.position();
            this.lastFlushTime = System.currentTimeMillis();
        } else if (!this.packetQueue.isEmpty()) {
            this.serverPos = this.packetQueue.get(0).pos;
        }
    }

    private void discardQueue() {
        this.packetQueue.clear();
        this.serverPos = mc.player != null ? mc.player.position() : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void sendQueued(Packet<?> packet) {
        try {
            if (mc.getConnection() == null) {
                return;
            }
            Packet raw = packet;
            PacketUtil.queuedPackets.add(raw);
            mc.getConnection().send(raw);
        } catch (Exception ignored) {
        }
    }

    private boolean isInteractPacket(Packet<?> packet) {
        return packet instanceof ServerboundUseItemOnPacket
                || packet instanceof ServerboundPlayerActionPacket;
    }

    private Vec3 extractPos(ServerboundMovePlayerPacket packet) {
        if (packet instanceof ServerboundMovePlayerPacket.PosRot p) {
            return new Vec3(p.getX(0), p.getY(0), p.getZ(0));
        }
        if (packet instanceof ServerboundMovePlayerPacket.Pos p) {
            return new Vec3(p.getX(0), p.getY(0), p.getZ(0));
        }
        return null;
    }

    private Vec3 firstQueuedPos() {
        return this.packetQueue.isEmpty() ? null : this.packetQueue.get(0).pos;
    }

    private List<Vec3> getQueuedPositions() {
        List<Vec3> positions = new ArrayList<>();
        if (this.serverPos != null) {
            positions.add(this.serverPos);
        }
        for (QueuedPacket entry : this.packetQueue) {
            if (entry.pos != null) {
                positions.add(entry.pos);
            }
        }
        if (mc.player != null) {
            positions.add(mc.player.position());
        }
        return positions;
    }

    private void drawTrail(PoseStack poseStack, List<Vec3> positions) {
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.setShader(GameRenderer::getRendertypeLinesShader);
        RenderSystem.lineWidth(2.5f);
        RenderSystem.disableCull();

        Matrix4f matrix = poseStack.last().pose();
        org.joml.Matrix3f normalMat = poseStack.last().normal();
        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

        float r = 0.0f, g = 0.9f, b = 1.0f, a = 0.75f;
        double offset = 0.5;

        for (int i = 0; i < positions.size() - 1; i++) {
            Vec3 start = positions.get(i);
            Vec3 end = positions.get(i + 1);
            float x1 = (float) start.x, y1 = (float) (start.y + offset), z1 = (float) start.z;
            float x2 = (float) end.x, y2 = (float) (end.y + offset), z2 = (float) end.z;
            float dx = x2 - x1, dy = y2 - y1, dz = z2 - z1;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float nx = len > 0 ? dx / len : 0f;
            float ny = len > 0 ? dy / len : 0f;
            float nz = len > 0 ? dz / len : 0f;
            builder.vertex(matrix, x1, y1, z1).color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
            builder.vertex(matrix, x2, y2, z2).color(r, g, b, a).normal(normalMat, nx, ny, nz).endVertex();
        }

        Tesselator.getInstance().end();
        RenderSystem.enableCull();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        poseStack.popPose();
    }

    public boolean isLagging() {
        return !this.packetQueue.isEmpty();
    }

    public Vec3 getServerPos() {
        return this.serverPos;
    }
}
