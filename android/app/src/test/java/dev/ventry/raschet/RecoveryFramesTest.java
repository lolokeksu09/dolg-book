package dev.ventry.raschet;

import org.junit.Test;
import java.io.*;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class RecoveryFramesTest {
    private final SecretKeySpec key=new SecretKeySpec(new byte[32],"AES");
    private byte[] encrypt(byte[] data,long limit) throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        RecoveryFrames.Writer writer=new RecoveryFrames.Writer(out,key,limit);
        writer.write(data);writer.finish();
        return out.toByteArray();
    }
    private byte[] decrypt(byte[] data,long limit) throws Exception{
        ByteArrayInputStream raw=new ByteArrayInputStream(data);
        byte[] magic=new byte[4];new DataInputStream(raw).readFully(magic);
        assertArrayEquals(RecoveryFrames.MAGIC,magic);
        try(RecoveryFrames.Reader reader=new RecoveryFrames.Reader(raw,key,limit)){
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            byte[] buffer=new byte[8192];int n;
            while((n=reader.read(buffer))!=-1)out.write(buffer,0,n);
            return out.toByteArray();
        }
    }
    private void rejected(byte[] data,long limit) throws Exception{
        try{decrypt(data,limit);fail("Invalid stream was accepted");}catch(IOException expected){}
    }
    @Test public void boundaryAndRoundTrip() throws Exception{
        for(int length:new int[]{0,1,RecoveryFrames.CHUNK-1,RecoveryFrames.CHUNK,RecoveryFrames.CHUNK+1,RecoveryFrames.CHUNK*2}){
            byte[] input=new byte[length];new java.util.Random(31).nextBytes(input);
            byte[] wire=encrypt(input,length);
            assertEquals(RecoveryFrames.maxFileBytes(length),wire.length);
            assertArrayEquals(input,decrypt(wire,length));
        }
        try{encrypt(new byte[2],1);fail("Oversized write");}catch(IOException expected){}
        rejected(encrypt(new byte[2],2),1);
    }
    @Test public void tamperingReorderingAndTruncationFailClosed() throws Exception{
        int block=RecoveryFrames.CHUNK+RecoveryFrames.FRAME_OVERHEAD;
        int header=RecoveryFrames.HEADER_BYTES;
        byte[] source=new byte[RecoveryFrames.CHUNK*2];
        byte[] wire=encrypt(source,source.length);
        byte[] tampered=wire.clone();tampered[header+20]^=1;rejected(tampered,source.length);
        tampered=wire.clone();tampered[wire.length-1]^=1;rejected(tampered,source.length);
        rejected(Arrays.copyOf(wire,wire.length-1),source.length);
        rejected(Arrays.copyOf(wire,wire.length+1),source.length);
        tampered=wire.clone();
        System.arraycopy(wire,header+block,tampered,header,block);
        System.arraycopy(wire,header,tampered,header+block,block);
        rejected(tampered,source.length);
        byte[] other=encrypt(source,source.length);
        tampered=wire.clone();System.arraycopy(other,header,tampered,header,block);
        rejected(tampered,source.length);
    }
}
