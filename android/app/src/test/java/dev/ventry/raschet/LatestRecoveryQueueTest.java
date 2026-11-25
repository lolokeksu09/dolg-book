package dev.ventry.raschet;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LatestRecoveryQueueTest {
    @Test public void burstKeepsLatestAndRequestsDuringRunningTask(){
        ArrayDeque<Runnable> executor=new ArrayDeque<>();
        LatestRecoveryQueue queue=new LatestRecoveryQueue(executor::add);
        List<Integer> calls=new ArrayList<>();
        queue.submit(()->calls.add(1));
        queue.submit(()->calls.add(2));
        queue.submit(()->{
            calls.add(3);
            queue.submit(()->calls.add(4));
            queue.submit(()->calls.add(5));
        });
        assertEquals(1,executor.size());
        executor.remove().run();
        assertEquals(Arrays.asList(3,5),calls);
        assertTrue(executor.isEmpty());
        queue.submit(()->calls.add(6));
        executor.remove().run();
        assertEquals(Arrays.asList(3,5,6),calls);
    }
    @Test public void failureDoesNotStrandNewerSnapshot(){
        ArrayDeque<Runnable> executor=new ArrayDeque<>();
        LatestRecoveryQueue queue=new LatestRecoveryQueue(executor::add);
        List<Integer> calls=new ArrayList<>();
        queue.submit(()->{
            queue.submit(()->calls.add(2));
            throw new IllegalStateException("synthetic");
        });
        try{executor.remove().run();fail("failure expected");}
        catch(IllegalStateException expected){assertEquals("synthetic",expected.getMessage());}
        assertEquals(1,executor.size());
        executor.remove().run();
        assertEquals(Arrays.asList(2),calls);
    }
}
