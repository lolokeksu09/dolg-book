package dev.ventry.raschet.cloud

import android.app.Application
import dev.ventry.raschet.LedgerStore
import dev.ventry.raschet.data.ClientCommand.FileTask
import java.io.ByteArrayOutputStream
import java.io.OutputStreamWriter
import java.io.IOException
import javax.crypto.AEADBadTagException

/** Typed failure: a local book may be valid but exceed the encrypted cloud envelope. */
internal class CloudSnapshotTooLarge : IOException(
    "Книга превышает размер облачной копии. Используйте JSON-экспорт."
)

/** Enforce the byte limit while serializing, not after allocating the entire book. */
internal class CloudSnapshotBuffer(private val limit:Int=CloudCipher.MAX_PLAIN):ByteArrayOutputStream(){
    override fun write(b:Int){
        if(count>=limit)throw CloudSnapshotTooLarge()
        super.write(b)
    }
    override fun write(b:ByteArray,off:Int,len:Int){
        if(off<0 || len<0 || off>b.size-len)throw IndexOutOfBoundsException()
        if(len>limit-count)throw CloudSnapshotTooLarge()
        super.write(b,off,len)
    }
    fun wipe(){buf.fill(0);reset()}
}

/** BookViewModel invokes this inside the existing serialized file/write boundary. */
internal object CloudBookService{
    fun snapshot(ledger:LedgerStore):ByteArray{
        val out=CloudSnapshotBuffer()
        val plain=try{
            val writer=OutputStreamWriter(out,Charsets.UTF_8).buffered()
            ledger.writeJson(writer);writer.flush()
            out.toByteArray()
        }finally{out.wipe()}
        return plain
    }
    fun save(app:Application,ledger:LedgerStore,task:FileTask.Cloud.Put){
        val plain=snapshot(ledger)
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
