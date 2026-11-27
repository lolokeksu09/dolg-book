package dev.ventry.raschet.cloud

import android.app.Application
import dev.ventry.raschet.LedgerStore
import dev.ventry.raschet.data.ClientCommand.FileTask
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.IOException
import javax.crypto.AEADBadTagException

/** BookViewModel invokes this inside the existing serialized file/write boundary. */
internal object CloudBookService{
    private class WipingBuffer:ByteArrayOutputStream(){
        override fun write(b:Int){
            if(count>=CloudCipher.MAX_PLAIN)throw IOException("Книга превышает размер облачной копии. Используйте JSON-экспорт.")
            super.write(b)
        }
        override fun write(b:ByteArray,off:Int,len:Int){
            if(len>CloudCipher.MAX_PLAIN-count)throw IOException("Книга превышает размер облачной копии. Используйте JSON-экспорт.")
            super.write(b,off,len)
        }
        fun wipe(){buf.fill(0);reset()}
    }
    fun save(app:Application,ledger:LedgerStore,task:FileTask.Cloud.Put){
        val out=WipingBuffer()
        val plain=try{
            val writer=OutputStreamWriter(out,Charsets.UTF_8).buffered()
            ledger.writeJson(writer);writer.flush()
            out.toByteArray()
        }finally{out.wipe()}
        val encrypted=try{CloudCipher.encrypt(plain,task.passphrase)}finally{plain.fill(0)}
        try{CloudBackupClient.put(app,task.slot,task.expected,encrypted)}finally{encrypted.fill(0)}
    }
    fun open(app:Application,task:FileTask.Cloud.Restore):ByteArray{
        val encrypted=CloudBackupClient.read(app,task.slot,task.version,task.size,task.sha256)
        return try{
            CloudCipher.decrypt(encrypted,task.passphrase)
        }catch(e:AEADBadTagException){
            throw IllegalArgumentException("Неверный пароль или повреждённая копия.")
        }finally{encrypted.fill(0)}
    }
}
