package dev.ventry.raschet;

import java.io.*;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** VRC2 authenticated framed stream. Caller owns the underlying output; input owns its source. */
final class RecoveryFrames {
    static final byte[] MAGIC={'V','R','C','2'};
    static final int CHUNK=64*1024, ID_BYTES=16, IV_BYTES=12, TAG_BYTES=16;
    static final int HEADER_BYTES=4+ID_BYTES, FRAME_OVERHEAD=4+IV_BYTES+TAG_BYTES;
    private RecoveryFrames(){}
    private static byte[] aad(byte[] id,int index,int length){
        return ByteBuffer.allocate(HEADER_BYTES+8).put(MAGIC).put(id).putInt(index).putInt(length).array();
    }
    static long maxFileBytes(long limit){
        return HEADER_BYTES+limit+((limit+CHUNK-1)/CHUNK+1)*FRAME_OVERHEAD;
    }
    static final class Writer extends OutputStream {
        private final DataOutputStream out;
        private final SecretKey key;
        private final long limit;
        private final byte[] id=new byte[ID_BYTES], buffer=new byte[CHUNK];
        private int size,index;
        private long count;
        private boolean finished;
        Writer(OutputStream output,SecretKey key,long limit) throws IOException{
            this.out=new DataOutputStream(output);this.key=key;this.limit=limit;
            new SecureRandom().nextBytes(id);out.write(MAGIC);out.write(id);
        }
        @Override public void write(int value) throws IOException{
            byte[] one={(byte)value};write(one,0,1);
        }
        @Override public void write(byte[] data,int offset,int length) throws IOException{
            if(finished)throw new IOException("Recovery stream finished");
            if(offset<0||length<0||offset>data.length-length)throw new IndexOutOfBoundsException();
            if(length>limit-count)throw new IOException("Размер книги превышает лимит резервирования.");
            count+=length;
            while(length>0){
                int n=Math.min(length,CHUNK-size);
                System.arraycopy(data,offset,buffer,size,n);
                size+=n;offset+=n;length-=n;
                if(size==CHUNK){frame(size);size=0;}
            }
        }
        private void frame(int length) throws IOException{
            try{
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                // Android Keystore supplies a fresh random IV; never reuse a caller-supplied nonce.
                cipher.init(Cipher.ENCRYPT_MODE,key);
                byte[] iv=cipher.getIV();
                if(iv.length!=IV_BYTES)throw new IOException("Неверный IV.");
                cipher.updateAAD(aad(id,index,length));
                byte[] ciphertext=cipher.doFinal(buffer,0,length);
                out.writeInt(length);out.write(iv);out.write(ciphertext);index++;
            }catch(GeneralSecurityException ex){throw new IOException("Не удалось зашифровать копию.",ex);}
        }
        void finish() throws IOException{
            if(finished)throw new IOException("Recovery stream finished");
            if(size>0)frame(size);
            frame(0);finished=true;out.flush();
        }
    }

    /** Reads the stream after the four-byte magic has been consumed. */
    static final class Reader extends InputStream {
        private final DataInputStream in;
        private final SecretKey key;
        private final long limit;
        private final byte[] id=new byte[ID_BYTES];
        private byte[] plain=new byte[0];
        private int offset,index;
        private long count;
        private boolean ended,shortFrame;
        Reader(InputStream source,SecretKey key,long limit) throws IOException{
            this.in=new DataInputStream(source);this.key=key;this.limit=limit;in.readFully(id);
        }
        private boolean next() throws IOException{
            if(ended)return false;
            int length=in.readInt();
            if(length<0||length>CHUNK||length>limit-count||(shortFrame&&length!=0))
                throw new IOException("Неверный размер блока копии.");
            byte[] iv=new byte[IV_BYTES],ciphertext=new byte[length+TAG_BYTES];
            in.readFully(iv);in.readFully(ciphertext);
            try{
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,iv));
                cipher.updateAAD(aad(id,index,length));
                plain=cipher.doFinal(ciphertext);
            }catch(GeneralSecurityException ex){throw new IOException("Повреждённая или подменённая копия.",ex);}
            index++;offset=0;count+=length;
            if(length==0){
                if(in.read()!=-1)throw new IOException("Лишние данные после конца копии.");
                ended=true;return false;
            }
            shortFrame=length<CHUNK;
            return true;
        }
        @Override public int read() throws IOException{
            if(offset==plain.length&&!next())return -1;
            return plain[offset++]&255;
        }
        @Override public int read(byte[] target,int start,int length) throws IOException{
            if(start<0||length<0||start>target.length-length)throw new IndexOutOfBoundsException();
            if(length==0)return 0;
            if(offset==plain.length&&!next())return -1;
            int n=Math.min(length,plain.length-offset);
            System.arraycopy(plain,offset,target,start,n);offset+=n;return n;
        }
        @Override public void close() throws IOException{in.close();}
    }
}
