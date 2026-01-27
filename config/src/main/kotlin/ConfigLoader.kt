package org.nxtspec

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.addResourceSource

object ConfigLoader {
    fun load(path: String = "queuebox.yml"): QueueBoxConfig {
        val config = ConfigLoaderBuilder.default()
            .addResourceSource("/$path")
            .build()
            .loadConfigOrThrow<QueueBoxConfig>()
        return ConfigValidator.validate(config)
    }
}
