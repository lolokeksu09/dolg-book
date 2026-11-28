package dev.ventry.raschet.cloud

import android.app.job.JobParameters
import android.app.job.JobService
import java.util.concurrent.atomic.AtomicBoolean

/** Android schedules best-effort daily unmetered work, even after restart. */
class CloudBackupJob:JobService(){
    private var active:AtomicBoolean?=null
    override fun onStartJob(params:JobParameters):Boolean{
        val stop=AtomicBoolean(false)
        active?.set(true)
        active=stop
        Thread({
            try{AutoBackup.run(application){stop.get()}}
            finally{
                android.os.Handler(mainLooper).post{
                    if(active===stop && !stop.get()){
                        active=null
                        jobFinished(params,false)
                    }
                }
            }
        },"RaschetCloudBackup").start()
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean{
        active?.set(true);active=null
        // No immediate retry of an upload with ambiguous completion.
        return false
    }
}
