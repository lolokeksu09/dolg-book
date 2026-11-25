package dev.ventry.raschet;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Device-bound recovery. New points use framed VRC2; existing VRC1 remains readable. */
public final class RecoveryVault {
    private static final String LEGACY_ALIAS="raschet.recovery.aes.v1";
    private static final String ALIAS="raschet.recovery.aes.v2";
    private static final byte[] LEGACY_MAGIC={'V','R','C','1'};
    private static final int IV_SIZE=12;
    private static final Object FILE_LOCK=new Object();
    private final File dir;

    public RecoveryVault(Context context){this(new File(context.getFilesDir(),"recovery-points"));}
    RecoveryVault(File root){dir=root;}

    public File create(LedgerStore ledger) throws Exception{
        synchronized(FILE_LOCK){return writePoint(ledger::writeJson);}
    }
    File createJson(String json) throws Exception{
        synchronized(FILE_LOCK){
            if(json==null)throw new IllegalArgumentException("Пустая копия.");
            JSONObject checked=new JSONObject(json);
            if(!checked.has("version")||!checked.has("clients")||!checked.has("entries"))
                throw new IOException("Недопустимая книга.");
            return writePoint(writer->writer.write(json));
        }
    }
    private interface PointWriter{void write(Writer writer) throws Exception;}

    /** No plaintext temporary file; publish only after authenticated EOF and fsync. */
    private File writePoint(PointWriter source) throws Exception{
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Нет папки для копии.");
        SecretKey key=key(ALIAS,true);
        String name="recovery-"+System.currentTimeMillis()+"-"+System.nanoTime()+".vrc";
        File target=new File(dir,name),tmp=new File(dir,name+".tmp");
        try{
            try(FileOutputStream stream=new FileOutputStream(tmp)){
                RecoveryFrames.Writer encrypted=new RecoveryFrames.Writer(stream,key,BackupSize.MAX_BYTES);
                Writer writer=new BufferedWriter(new OutputStreamWriter(encrypted,StandardCharsets.UTF_8));
                source.write(writer);
                writer.flush();
                encrypted.finish();
                stream.getFD().sync();
            }
            if(!tmp.renameTo(target))throw new IOException("Не удалось записать копию.");
        }finally{tmp.delete();}
        List<File> points=points();
        for(int i=4;i<points.size();i++)points.get(i).delete();
        return target;
    }
    public boolean hasPoints(){return !points().isEmpty();}
    public int count(){return points().size();}
    public long latestTime(){
        List<File> points=points();
        return points.isEmpty()?0L:points.get(0).lastModified();
    }
    /** Open the selected immutable file before creating rollback; rotation may unlink it safely. */
    public Reader openLatest() throws Exception{
        synchronized(FILE_LOCK){
            List<File> points=points();
            if(points.isEmpty())throw new IOException("Нет защищённых точек.");
            return openLocked(points.get(0));
        }
    }
    Reader open(File file) throws Exception{
        synchronized(FILE_LOCK){return openLocked(file);}
    }

    // Compatibility helpers for small QA fixtures; production restores use openLatest().
    public String readLatest() throws Exception{try(Reader reader=openLatest()){return readAll(reader);}}
    String read(File file) throws Exception{try(Reader reader=open(file)){return readAll(reader);}}
    private static String readAll(Reader reader) throws IOException{
        StringBuilder result=new StringBuilder();
        char[] buffer=new char[8192];int n;
        while((n=reader.read(buffer))!=-1)result.append(buffer,0,n);
        return result.toString();
    }
    private Reader openLocked(File file) throws Exception{
        if(!file.getParentFile().equals(dir))throw new SecurityException("Недоступная точка.");
        long size=file.length();
        if(size<4+IV_SIZE+16||size>RecoveryFrames.maxFileBytes(BackupSize.MAX_BYTES))
            throw new IOException("Неверный размер точки.");
        DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(file)));
        boolean handedOff=false;
        try{
            byte[] magic=new byte[4];input.readFully(magic);
            if(Arrays.equals(magic,RecoveryFrames.MAGIC)){
                Reader reader=BackupSize.openUtf8(new RecoveryFrames.Reader(input,key(ALIAS,false),BackupSize.MAX_BYTES));
                handedOff=true;
                return reader;
            }
            if(!Arrays.equals(magic,LEGACY_MAGIC))
                throw new IOException("Неподдерживаемый формат.");
            if(size>BackupSize.LEGACY_MAX_BYTES+4L+IV_SIZE+16)
                throw new IOException("Неверный размер старой точки.");
            byte[] iv=new byte[IV_SIZE],ciphertext=new byte[(int)size-4-IV_SIZE];
            input.readFully(iv);input.readFully(ciphertext);
            if(input.read()!=-1)throw new IOException("Неверный размер старой точки.");
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(LEGACY_ALIAS,false),new GCMParameterSpec(128,iv));
            return BackupSize.openUtf8(new ByteArrayInputStream(cipher.doFinal(ciphertext)));
        }finally{if(!handedOff)input.close();}
    }
    private List<File> points(){
        File[] entries=dir.listFiles(f->f.isFile()&&f.getName().startsWith("recovery-")&&f.getName().endsWith(".vrc"));
        List<File> items=new ArrayList<>();
        if(entries!=null)items.addAll(Arrays.asList(entries));
        items.sort(Comparator.comparing(File::getName).reversed());
        return items;
    }
    private SecretKey key(String alias,boolean create) throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key saved=store.getKey(alias,null);
        if(saved instanceof SecretKey)return (SecretKey)saved;
        if(!create)throw new IOException("Ключ защищённой точки недоступен.");
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }
}
