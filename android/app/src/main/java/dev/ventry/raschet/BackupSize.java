package dev.ventry.raschet;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;

/** Shared UTF-8 plaintext boundary for portable JSON and local recovery. */
final class BackupSize {
    static final int LEGACY_MAX_BYTES=10*1024*1024;
    static final int MAX_MIB=256;
    static final int MAX_BYTES=MAX_MIB*1024*1024;
    private BackupSize(){}

    /** The returned reader owns the stream. Count actual bytes, including malformed input. */
    static Reader openUtf8(InputStream source){
        InputStream bounded=new FilterInputStream(source){
            private long count;
            @Override public int read() throws IOException{
                int value=in.read();
                if(value!=-1)requireSupported(++count);
                return value;
            }
            @Override public int read(byte[] bytes,int offset,int length) throws IOException{
                int request=(int)Math.min(length,MAX_BYTES-count+1L);
                int n=in.read(bytes,offset,request);
                if(n>0){count+=n;requireSupported(count);}
                return n;
            }
        };
        return new InputStreamReader(bounded,StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
    }
    /** Compatibility helper; the caller continues to own the supplied input stream. */
    static String readUtf8(InputStream source) throws IOException {
        Reader reader=openUtf8(source);
        StringBuilder result=new StringBuilder();
        char[] buffer=new char[8192];
        int n;
        while((n=reader.read(buffer))!=-1)result.append(buffer,0,n);
        return result.toString();
    }
    static boolean isSupported(long bytes){return bytes>=0 && bytes<=MAX_BYTES;}
    static void requireSupported(long bytes) throws IOException {
        if(!isSupported(bytes))throw new IOException("Размер книги больше "+MAX_MIB+" МиБ.");
    }
}
