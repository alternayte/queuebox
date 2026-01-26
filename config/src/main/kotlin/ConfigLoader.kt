package org.nxtspec

import com.sksamuel.hoplite.*


object ConfigLoader {
    fun load(path: String = "queuebox.yml"): QueueBoxConfig {
        return ConfigLoader().loadConfigOrThrow<QueueBoxConfig>("/$path")
    }

}