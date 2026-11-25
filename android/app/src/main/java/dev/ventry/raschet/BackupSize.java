package dev.ventry.raschet;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;

/** Shared UTF-8 plaintext boundary for portable JSON and local recovery. */
final class BackupSize {
    static final int MAX_MIB=10;
    static final int MAX_BYTES=MAX_MIB*1024*1024;
    private BackupSize(){}
    /** Reject oversized files during reading; malformed UTF-8 is not silently substituted. */
    static String readUtf8(InputStream source) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        int n;
        while((n=source.read(buffer))!=-1){
            if(n==0)continue;
            requireSupported((long)bytes.size()+n);
            bytes.write(buffer,0,n);
        }
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
    }
    static boolean isSupported(long bytes){return bytes>=0 && bytes<=MAX_BYTES;}
    static void requireSupported(long bytes) throws IOException {
        if(!isSupported(bytes))throw new IOException("Размер книги больше "+MAX_MIB+" МиБ.");
    }
}
