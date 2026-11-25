package dev.ventry.raschet;

import java.io.IOException;

/** Shared UTF-8 plaintext boundary for portable JSON and local recovery. */
final class BackupSize {
    static final int MAX_MIB=10;
    static final int MAX_BYTES=MAX_MIB*1024*1024;
    private BackupSize(){}
    static boolean isSupported(long bytes){return bytes>=0 && bytes<=MAX_BYTES;}
    static void requireSupported(long bytes) throws IOException {
        if(!isSupported(bytes))throw new IOException("Размер книги больше "+MAX_MIB+" МиБ.");
    }
}
