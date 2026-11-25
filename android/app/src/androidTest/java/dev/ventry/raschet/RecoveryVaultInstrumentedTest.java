package dev.ventry.raschet;
import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RecoveryVaultInstrumentedTest {
    private Context ctx;
    private LedgerStore book;
    private RecoveryVault vault;
    private File dir;
    private static final String DB="recovery-qa-only.db";
    @Before public void before(){
        ctx=InstrumentationRegistry.getInstrumentation().getTargetContext();
        ctx.deleteDatabase(DB);
        book=new LedgerStore(ctx,DB);
        dir=new File(ctx.getCacheDir(),"recovery-qa-"+System.nanoTime());
        vault=new RecoveryVault(dir);
    }
    @After public void after(){
        if(book!=null)book.close();
        if(ctx!=null)ctx.deleteDatabase(DB);
        if(dir!=null){File[] files=dir.listFiles();if(files!=null)for(File f:files)f.delete();dir.delete();}
    }
    @Test public void encryptedRoundtripRestoresOriginalBalance() throws Exception{
        book.addClient("Фиктивный","123");
        String id=book.clients().get(0).id;
        book.addEntry(id,"debt",23456L,"","");
        String snapshot=book.exportJson();
        File point=vault.create(book);
        assertEquals(1,vault.count());
        assertFalse(new String(Files.readAllBytes(point.toPath()),"UTF-8").contains("Фиктивный"));
        book.addEntry(id,"payment",456L,"","");
        assertEquals(23000L,LedgerStore.balance(book.entries(),id));
        assertEquals(snapshot,vault.readLatest());
        book.importJson(vault.readLatest());
        assertEquals(23456L,LedgerStore.balance(book.entries(),id));
    }
    @Test public void authenticatedCorruptionDoesNotMutateBook() throws Exception{
        book.addClient("Тестовый","");
        File point=vault.create(book);
        byte[] data=Files.readAllBytes(point.toPath());
        data[data.length-1]^=1;
        Files.write(point.toPath(),data);
        String original=book.exportJson();
        try(java.io.Reader input=vault.openLatest()){
            book.importJson(input);
            fail("Corrupt authenticated EOF must roll back");
        }catch(java.io.IOException expected){assertEquals(original,book.exportJson());}
    }
    @Test public void keepLastFour() throws Exception{
        book.addClient("Тестовый","");
        for(int i=0;i<6;i++)vault.create(book);
        assertEquals(4,vault.count());
    }
    @Test public void readsOriginalVrc1WithoutMigratingOrReplacingItsKey() throws Exception{
        book.addClient("Старая книга","");
        String snapshot=book.exportJson();
        String alias="raschet.recovery.aes.v1";
        java.security.KeyStore ks=java.security.KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        javax.crypto.SecretKey key=(javax.crypto.SecretKey)ks.getKey(alias,null);
        if(key==null){
            javax.crypto.KeyGenerator generator=javax.crypto.KeyGenerator.getInstance("AES","AndroidKeyStore");
            generator.init(new android.security.keystore.KeyGenParameterSpec.Builder(alias,
                    android.security.keystore.KeyProperties.PURPOSE_ENCRYPT|android.security.keystore.KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).setKeySize(256).build());
            key=generator.generateKey();
        }
        javax.crypto.Cipher cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE,key);
        assertTrue(dir.mkdirs()||dir.isDirectory());
        File legacy=new File(dir,"recovery-legacy.vrc");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(legacy)){
            output.write(new byte[]{'V','R','C','1'});
            output.write(cipher.getIV());
            output.write(cipher.doFinal(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        assertEquals(snapshot,vault.read(legacy));
        File current=vault.create(book);
        assertEquals(snapshot,vault.read(current));
        assertEquals(snapshot,vault.read(legacy));
    }
}
