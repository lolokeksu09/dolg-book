package dev.ventry.raschet.cloud

/** Automatic mode can only replace the exact version it last acknowledged. */
internal object AutoBackupPolicy {
    const val SLOT=3
    enum class Action{STOP,SKIP,UPLOAD}
    fun decide(binding:String,currentBinding:String,version:String,currentVersion:String?,previousHash:String,currentHash:String):Action =
        when{
            binding!=currentBinding || version!=currentVersion -> Action.STOP
            previousHash==currentHash -> Action.SKIP
            else -> Action.UPLOAD
        }
}
