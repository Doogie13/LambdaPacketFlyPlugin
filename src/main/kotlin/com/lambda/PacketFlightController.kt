package com.lambda

import com.lambda.client.plugin.api.Plugin
import com.lambda.modules.PacketFlight

internal object PacketFlightController : Plugin() {

    override fun onLoad() {
        // Load any modules, commands, or HUD elements here
        modules.add(PacketFlight)
    }
}