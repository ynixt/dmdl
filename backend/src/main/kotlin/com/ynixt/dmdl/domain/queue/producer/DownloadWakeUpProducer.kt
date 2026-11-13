package com.ynixt.dmdl.domain.queue.producer

import com.ynixt.dmdl.domain.model.DownloadWakeUp

interface DownloadWakeUpProducer {
    fun publish(message: DownloadWakeUp)
}
