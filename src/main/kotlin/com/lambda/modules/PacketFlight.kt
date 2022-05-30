package com.lambda.modules

import com.lambda.PacketFlightController
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.mixin.extension.playerPosLookPitch
import com.lambda.client.mixin.extension.playerPosLookYaw
import com.lambda.client.module.Category
import com.lambda.client.plugin.api.PluginModule
import com.lambda.client.util.MovementUtils.calcMoveYaw
import com.lambda.client.util.threads.safeListener
import net.minecraft.client.gui.*
import net.minecraft.client.multiplayer.GuiConnecting
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.math.Vec3d
import java.util.function.Supplier
import kotlin.math.*


/**
 * @author Doogie13
 * @since 30/05/2022
 */
object PacketFlight : PluginModule(
    name = "PacketFlight",
    category = Category.MOVEMENT,
    description = "MOVES YOU",
    pluginMain = PacketFlightController
) {

    private val mode by setting("Mode", Mode.FACTOR)
    private val factor by setting("Factor", 1.0, 0.0..3.0, .1)
    private val conceal by setting("Conceal", false)
    private val timeOut by setting("Lag Back Timeout", 5, 0..50, 1)
    private val shouldAntiKick by setting("AntiKick", false)

    @Suppress("Unused")
    enum class Mode {
        FAST, FACTOR, SETBACK
    }

    private var allowed: ArrayList<CPacketPlayer.Position> = ArrayList()

    // history of packets we are yet to respond to
    private var history: HashMap<Int, Vec3d> = HashMap()

    // base move speed
    private const val MOVE_SPEED = .2873

    // .0001 slower than server will check for
    private const val CONCEAL_SPEED = .0624

    private val walls: Supplier<Boolean> = Supplier<Boolean> { mc.world.collidesWithAnyBlock(mc.player.entityBoundingBox) }

    private val antiKick: Supplier<Boolean> = Supplier<Boolean> {
        shouldAntiKick &&
            !(mc.player.posY % 1 == 0.0 && mc.world.collidesWithAnyBlock(mc.player.entityBoundingBox.offset(0.0, -.01, 0.0))) && !walls.get()
    }

    private var tpID = -69

    private var timer = 0

    init {

        onEnable {
            allowed = ArrayList()

            tpID = -69

            history.clear()
        }

        onDisable {
            if (mc.player != null) {
                mc.player.connection.sendPacket(CPacketPlayer.Rotation(mc.player.rotationYaw, mc.player.rotationPitch, mc.player.onGround))
                mc.player.setVelocity(0.0, 0.0, 0.0)
            }
        }

        safeListener<PlayerMoveEvent> {

            if (mc.currentScreen is GuiMainMenu || mc.currentScreen is GuiDisconnected || mc.currentScreen is GuiConnecting || mc.currentScreen is GuiDownloadTerrain) {
                disable()
                return@safeListener
            }

            timer--

            // never apply speed when concealing due to the fact concealing is unrelated to anticheat move check
            var speed = if (conceal || walls.get() || timer > 0) CONCEAL_SPEED else MOVE_SPEED

            var thisFactor = floor(factor).toInt()

            if (mode == Mode.FACTOR) {
                if (mc.player.ticksExisted % 10 < 10 * (factor - floor(factor))) thisFactor++
                if (mc.player.moveStrafing == 0f && mc.player.moveForward == 0f) {
                    if (!mc.gameSettings.keyBindJump.isKeyDown && !mc.gameSettings.keyBindSneak.isKeyDown) thisFactor = 1
                }
            } else thisFactor = 1

            var y = 0.0

            if (mc.gameSettings.keyBindJump.isKeyDown) {
                if (!walls.get()) {
                    y = CONCEAL_SPEED
                    speed = 0.0
                } else {
                    // 0.707 is sqrt(2)/2
                    speed = CONCEAL_SPEED * 0.707
                    y = speed
                }
            } else if (mc.gameSettings.keyBindSneak.isKeyDown) {
                if (!walls.get()) {
                    y = -CONCEAL_SPEED
                } else {
                    y = -CONCEAL_SPEED * 0.707
                    speed = CONCEAL_SPEED * 0.707
                }
            }

            val antiKicks = mc.player.ticksExisted % 40 == 0 && antiKick.get()

            if (hypot(speed, y) > .2873) {
                speed = sqrt(speed * speed - y * y)
            }

            sendPackets(speed, y, thisFactor, antiKicks)

            mc.player.noClip = true

        }

        safeListener<PacketEvent.Send> { event ->

            if (event.packet is CPacketPlayer) {
                if (event.packet !is CPacketPlayer.Position) {
                    event.cancel()
                    return@safeListener
                }
                if (!allowed.contains(event.packet as CPacketPlayer.Position)) {
                    event.cancel()
                } else {
                    allowed.remove(event.packet as CPacketPlayer.Position)
                }
            }

        }

        safeListener<PacketEvent.Receive> { event ->

            if (event.packet is SPacketPlayerPosLook) {

                val packet = event.packet as SPacketPlayerPosLook
                val id = packet.teleportId

                if (history.containsKey(id)) {

                    val vec : Vec3d = history[id] ?: return@safeListener

                    if (vec.x == packet.x && vec.y == packet.y && vec.z == packet.z) {

                        if (mode != Mode.SETBACK)
                            event.cancel()

                        history.remove(id)

                        mc.player.connection.sendPacket(CPacketConfirmTeleport(id))

                        return@safeListener

                    }

                }

                packet.playerPosLookYaw = (mc.player.rotationYaw)
                packet.playerPosLookPitch = (mc.player.rotationPitch)

                mc.player.connection.sendPacket(CPacketConfirmTeleport(id))

                timer = timeOut

                tpID = id

            }

        }

    }

    private fun SafeClientEvent.sendPackets(speed: Double, y: Double, factor: Int, antiKicking: Boolean) {

        val yaw = calcMoveYaw()
        var motionX = -sin(yaw)
        var motionZ = cos(yaw)

        if (factor == 0) {
            mc.player.setVelocity(0.0, 0.0, 0.0)
            return
        }

        if (mc.player.moveForward == 0f && mc.player.moveStrafing == 0f) {
            motionX = .0
            motionZ = .0
        }

        for (currentFactor in 1 until factor + 1) {
            var thisY = y

            if (antiKicking) {
                thisY = -.04
            } else {
                thisY *= currentFactor
            }

            val moveVec = Vec3d(
                mc.player.posX + (motionX * speed * currentFactor),
                mc.player.posY + (thisY),
                mc.player.posZ + (motionZ * speed * currentFactor)
            )

            mc.player.setVelocity(moveVec.x - mc.player.posX, moveVec.y - mc.player.posY, moveVec.z - mc.player.posZ)

            val bounds = CPacketPlayer.Position(
                mc.player.posX,
                mc.player.posY - 1337,
                mc.player.posZ, true
            )

            val thisPacket = CPacketPlayer.Position(moveVec.x, moveVec.y, moveVec.z, true)
            allowed.add(thisPacket)
            mc.player.connection.sendPacket(thisPacket)
            allowed.add(bounds)
            mc.player.connection.sendPacket(bounds)
            history[++tpID] = moveVec
            mc.player.connection.sendPacket(CPacketConfirmTeleport(tpID))
        }

    }

}