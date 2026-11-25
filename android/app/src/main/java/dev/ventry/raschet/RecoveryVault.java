package dev.ventry.raschet;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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

/** Encrypted in-app recovery points. Key is lost on uninstall: not a portable backup. */
public final class RecoveryVault {
    private static final String ALIAS="raschet.recovery.aes.v1";
    private static final byte[] MAGIC={'V','R','C','1'};
    private static final int IV_SIZE=12, MAX_BYTES=BackupSize.MAX_BYTES;
    private static final Object FILE_LOCK=new Object();
    private final File dir;

    public RecoveryVault(Context context) {
        this(new File(context.getFilesDir(),"recovery-points"));
    }
    RecoveryVault(File root){dir=root;}

    public File create(LedgerStore ledger) throws Exception {
        synchronized(FILE_LOCK){return writePoint(ledger::writeJson);}
    }
    File createJson(String json) throws Exception {
        synchronized(FILE_LOCK){
            if(json==null)throw new IllegalArgumentException("Пустая копия.");
            JSONObject checked=new JSONObject(json);
            if(!checked.has("version")||!checked.has("clients")||!checked.has("entries"))
                throw new IOException("Недопустимая книга.");
            return writePoint(writer->writer.write(json));
        }
    }
    private interface PointWriter { void write(java.io.Writer writer) throws Exception; }

    /** VRC1: magic + IV + AES-GCM ciphertext/tag. No plaintext temporary file. */
    private File writePoint(PointWriter source) throws Exception {
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("Нет папки для копии.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV();
        if(iv.length!=IV_SIZE)throw new IOException("Неверный IV.");
        String name="recovery-"+System.currentTimeMillis()+"-"+System.nanoTime()+".vrc";
        File target=new File(dir,name),tmp=new File(dir,name+".tmp");
        try{
            try(FileOutputStream stream=new FileOutputStream(tmp)){
                stream.write(MAGIC);
                stream.write(iv);
                java.io.OutputStream encrypted=new java.io.OutputStream(){
                    private long plaintextBytes;
                    @Override public void write(int value) throws IOException{
                        write(new byte[]{(byte)value},0,1);
                    }
                    @Override public void write(byte[] data,int offset,int length) throws IOException{
                        BackupSize.requireSupported(plaintextBytes+length);
                        byte[] chunk=cipher.update(data,offset,length);
                        if(chunk!=null)stream.write(chunk);
                        plaintextBytes+=length;
                    }
                };
                java.io.Writer writer=new java.io.BufferedWriter(new java.io.OutputStreamWriter(encrypted,StandardCharsets.UTF_8));
                source.write(writer);
                writer.flush();
                // Explicit finalization: authentication/tag errors must abort before rename.
                stream.write(cipher.doFinal());
                stream.flush();stream.getFD().sync();
            }
            if(!tmp.renameTo(target))throw new IOException("Не удалось записать копию.");
        }finally{tmp.delete();}
        List<File> points=points();
        for(int i=4;i<points.size();i++)points.get(i).delete();
        return target;
    }
    public synchronized boolean hasPoints(){return !points().isEmpty();}
    public synchronized int count(){return points().size();}
    public synchronized long latestTime(){
        List<File> points=points();
        return points.isEmpty()?0L:points.get(0).lastModified();
    }
    public String readLatest() throws Exception {
        synchronized(FILE_LOCK){
            List<File> points=points();
            if(points.isEmpty())throw new IOException("Нет защищённых точек.");
            return readLocked(points.get(0));
        }
    }
    String read(File file) throws Exception {
        synchronized(FILE_LOCK){return readLocked(file);}
    }
    private String readLocked(File file) throws Exception {
        if(!file.getParentFile().equals(dir))throw new SecurityException("Недоступная точка.");
        if(file.length()<4+IV_SIZE+16||file.length()>MAX_BYTES+4+IV_SIZE+16)
            throw new IOException("Неверный размер точки.");
        byte[] bytes=Files.readAllBytes(file.toPath());
        if(!Arrays.equals(MAGIC,Arrays.copyOfRange(bytes,0,4)))
            throw new IOException("Неподдерживаемый формат.");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),
                new GCMParameterSpec(128,Arrays.copyOfRange(bytes,4,4+IV_SIZE)));
        String json=new String(cipher.doFinal(
                Arrays.copyOfRange(bytes,4+IV_SIZE,bytes.length)),StandardCharsets.UTF_8);
        JSONObject parsed=new JSONObject(json);
        if(!parsed.has("version")||!parsed.has("clients")||!parsed.has("entries"))
            throw new IOException("Повреждённая книга.");
        return json;
    }
    private List<File> points(){
        File[] entries=dir.listFiles(f->f.isFile()&&f.getName().startsWith("recovery-")
                &&f.getName().endsWith(".vrc"));
        List<File> items=new ArrayList<>();
        if(entries!=null)items.addAll(Arrays.asList(entries));
        items.sort(Comparator.comparing(File::getName).reversed());
        return items;
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key saved=store.getKey(ALIAS,null);
        if(saved instanceof SecretKey)return (SecretKey)saved;
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return generator.generateKey();
    }
}
