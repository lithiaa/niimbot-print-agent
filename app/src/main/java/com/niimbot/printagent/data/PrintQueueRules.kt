package com.niimbot.printagent.data

object PrintQueueRules {
    fun canCancel(status: PrintStatus): Boolean = status == PrintStatus.PENDING
}
