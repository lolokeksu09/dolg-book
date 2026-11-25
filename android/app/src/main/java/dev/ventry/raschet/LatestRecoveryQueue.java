package dev.ventry.raschet;

import java.util.concurrent.Executor;

/** At most one running and one pending snapshot. Pending requests are superseded by newer ones. */
final class LatestRecoveryQueue {
    private final Executor executor;
    private Runnable pending;
    private boolean scheduled;
    LatestRecoveryQueue(Executor executor){this.executor=executor;}
    void submit(Runnable task){
        synchronized(this){
            pending=task;
            if(scheduled)return;
            scheduled=true;
            try{executor.execute(this::drain);}
            catch(RuntimeException failure){scheduled=false;pending=null;throw failure;}
        }
    }
    private void drain(){
        try{
            while(true){
                Runnable task;
                synchronized(this){
                    task=pending;
                    pending=null;
                    if(task==null){scheduled=false;return;}
                }
                task.run();
            }
        }catch(RuntimeException|Error failure){
            synchronized(this){
                scheduled=false;
                if(pending!=null)submit(pending);
            }
            throw failure;
        }
    }
}
