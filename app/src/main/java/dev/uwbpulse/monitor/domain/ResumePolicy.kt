package dev.uwbpulse.monitor.domain

enum class ResumeReason { BOOT, PACKAGE_UPDATE, PROCESS_RESTART }
object ResumePolicy {
    fun shouldResume(enabled: Boolean, afterBoot: Boolean, reason: ResumeReason) = enabled && (reason != ResumeReason.BOOT || afterBoot)
}
