import dev.ventry.raschet.bridge.NativeLedger;
import java.util.Arrays;

public final class NativeLedgerSmoke {
    private static void equal(long[] actual,long... expected) {
        if(!Arrays.equals(actual,expected)) throw new AssertionError(Arrays.toString(actual)+" != "+Arrays.toString(expected));
    }
    private static long[] run(int mode,long[] rows,int owners,long a,long b,long c,int length) {
        long[] out=new long[length];
        int status=NativeLedger.compute(mode,rows,owners,a,b,c,out);
        if(status!=0) throw new AssertionError("Native mode "+mode+" status "+status);
        return out;
    }
    public static void main(String[] args) {
        System.load(new java.io.File(args[0]).getAbsolutePath());
        long N=NativeLedger.NONE;
        long[] rows={1,0,1,35000,9,0,10, 2,0,2,10000,N,0,11,
            3,1,1,5000,15,0,10, 4,0,3,10000,N,2,12, 5,0,2,10000,N,0,13};
        long[] projection=rows.clone();
        projection[4]=1;
        projection[18]=0;
        equal(run(1,projection,2,0,0,0,2),25000,25000);
        equal(run(1,projection,2,-1,0,0,2),0,0);
        equal(run(2,rows,2,0,10,13,1),25000);
        equal(run(3,rows,2,13,0,0,10),25000,25000,0,N,4,5000,0,5000,15,2);
        equal(run(4,rows,2,10,13,0,28),0,30000,40000,20000,0,10000,5,2,
            40000,0,0,0,2, 0,10000,0,0,1, 0,0,0,10000,1, 0,10000,0,0,1);
        equal(run(3,new long[0],0,13,0,0,0));
        equal(run(4,new long[0],0,10,10,0,13),0,0,0,0,0,0,0,0,0,0,0,0,0);
        long[] unchanged={999};
        if(NativeLedger.compute(1,rows,2,0,0,0,unchanged)==0)throw new AssertionError("short output accepted");
        equal(unchanged,999);
        if(NativeLedger.compute(1,null,0,0,0,0,unchanged)==0)throw new AssertionError("null input accepted");
        if(NativeLedger.compute(2,new long[]{1},0,0,0,0,unchanged)==0)throw new AssertionError("truncated row accepted");
        if(NativeLedger.compute(4,rows,2,13,10,0,unchanged)==0)throw new AssertionError("invalid period accepted");
        long[] broken=rows.clone();broken[26]=999;
        if(NativeLedger.compute(2,broken,2,0,10,13,unchanged)==0)throw new AssertionError("broken reversal accepted");
        equal(unchanged,999);
        System.out.println("NATIVE_LEDGER_BATCH_HOST_PASS");
    }
}
