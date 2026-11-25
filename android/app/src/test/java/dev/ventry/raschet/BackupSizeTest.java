package dev.ventry.raschet;

import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

public class BackupSizeTest {
    @Test public void sharedLimitAcceptsPreviouslyRejectedRecoverySize() throws Exception {
        assertTrue(BackupSize.isSupported(5L*1024*1024+1));
        BackupSize.requireSupported(BackupSize.MAX_BYTES);
        assertFalse(BackupSize.isSupported(BackupSize.MAX_BYTES+1L));
        assertFalse(BackupSize.isSupported(-1));
        try { BackupSize.requireSupported(BackupSize.MAX_BYTES+1L); fail("Oversized recovery must fail"); }
        catch(IOException expected) { assertTrue(expected.getMessage().contains(Integer.toString(BackupSize.MAX_MIB))); }
    }
    @Test public void boundedReaderPreservesRussianAndRejectsMalformedUtf8() throws Exception {
        String sample="{\"name\":\"Яблоки\"}";
        assertEquals(sample,BackupSize.readUtf8(new ByteArrayInputStream(sample.getBytes(StandardCharsets.UTF_8))));
        try {
            BackupSize.readUtf8(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}));
            fail("Malformed UTF-8 must not be replaced silently");
        } catch(java.nio.charset.CharacterCodingException expected) {}
    }

    @Test public void boundedReaderRejectsOneByteBeyondLimit() throws Exception {
        java.io.InputStream oversized=new java.io.InputStream(){
            private long remaining=BackupSize.MAX_BYTES+1L;
            @Override public int read(){if(remaining==0)return -1;remaining--;return ' ';}
            @Override public int read(byte[] data,int off,int len){
                if(len==0)return 0;
                if(remaining==0)return -1;
                int n=(int)Math.min(remaining,len);
                java.util.Arrays.fill(data,off,off+n,(byte)' ');remaining-=n;return n;
            }
        };
        try(java.io.Reader reader=BackupSize.openUtf8(oversized)){
            char[] buffer=new char[8192];
            while(reader.read(buffer)!=-1){}
            fail("Oversized backup accepted");
        }
        catch(IOException expected) { assertTrue(expected.getMessage().contains(Integer.toString(BackupSize.MAX_MIB))); }
    }
}
