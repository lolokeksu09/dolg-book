package dev.ventry.raschet.cloud

import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStreamWriter

class CloudSnapshotBufferTest {
    @Test fun exactLimitAndRejectedWritesDoNotChangeSnapshot(){
        val buffer=CloudSnapshotBuffer(4)
        try{
            buffer.write(1)
            buffer.write(byteArrayOf(2,3,4))
            assertArrayEquals(byteArrayOf(1,2,3,4),buffer.toByteArray())
            assertThrows(CloudSnapshotTooLarge::class.java){buffer.write(5)}
            assertThrows(CloudSnapshotTooLarge::class.java){buffer.write(byteArrayOf(5))}
            assertArrayEquals(byteArrayOf(1,2,3,4),buffer.toByteArray())
        }finally{buffer.wipe()}
        assertEquals(0,buffer.size())
    }
    @Test fun limitCountsUtf8BytesNotCharacters(){
        val buffer=CloudSnapshotBuffer(3)
        try{
            val writer=OutputStreamWriter(buffer,Charsets.UTF_8)
            writer.write("яя")
            assertThrows(CloudSnapshotTooLarge::class.java){writer.flush()}
            assertEquals(0,buffer.size())
        }finally{buffer.wipe()}
    }
    @Test fun invalidSliceIsNotReportedAsBookTooLarge(){
        val buffer=CloudSnapshotBuffer(1)
        try{
            assertThrows(IndexOutOfBoundsException::class.java){buffer.write(byteArrayOf(1),1,2)}
            assertEquals(0,buffer.size())
        }finally{buffer.wipe()}
    }
}
