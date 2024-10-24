package xtdb.arrow

import org.apache.arrow.memory.BufferAllocator
import org.apache.arrow.memory.RootAllocator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import xtdb.arrow.Relation.Companion.loader
import xtdb.arrow.RelationTest.ByteBufferChannel
import xtdb.toFieldType
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels

class StructVectorTest {
    private lateinit var allocator: BufferAllocator

    @BeforeEach
    fun setUp() {
        allocator = RootAllocator()
    }

    @AfterEach
    fun tearDown() {
        allocator.close()
    }

    @Test
    fun structVectorValues() {
        val children = linkedMapOf(
            "i32" to IntVector(allocator, "i32", false),
            "utf8" to Utf8Vector(allocator, "utf8", true)
        )

        val m1 = mapOf("i32" to 4, "utf8" to "Hello")
        val m2 = mapOf("i32" to 8)
        val m3 = mapOf("i32" to 12, "utf8" to "world!")

        StructVector(allocator, "struct", true, children).use { structVec ->
            structVec.writeObject(m1)
            structVec.writeObject(m2)
            structVec.writeObject(m3)

            assertEquals(listOf(m1, m2, m3), structVec.asList)
        }
    }

    @Test
    fun useNestedWriters() {
        val children = linkedMapOf(
            "i32" to IntVector(allocator, "i32", false),
            "utf8" to Utf8Vector(allocator, "utf8", true)
        )

        StructVector(allocator, "struct", false, children).use { structVec ->
            val i32Writer = structVec.keyWriter("i32")
            val utf8Writer = structVec.keyWriter("utf8")

            i32Writer.writeInt(4)
            utf8Writer.writeObject("Hello")
            structVec.endStruct()

            i32Writer.writeInt(8)
            structVec.endStruct()

            i32Writer.writeInt(12)
            utf8Writer.writeObject("world!")
            structVec.endStruct()

            assertEquals(
                listOf(
                    mapOf("i32" to 4, "utf8" to "Hello"),
                    mapOf("i32" to 8),
                    mapOf("i32" to 12, "utf8" to "world!")
                ),
                structVec.asList
            )
        }
    }

    @Test
    fun createsMissingVectors() {
        StructVector(allocator, "struct", false).use { structVec ->
            val i32Writer = structVec.keyWriter("i32", 4.toFieldType())

            i32Writer.writeInt(4)
            structVec.endStruct()

            val utf8Writer = structVec.keyWriter("utf8", "foo".toFieldType())

            i32Writer.writeInt(8)
            utf8Writer.writeObject("Hello")
            structVec.endStruct()

            i32Writer.writeInt(12)
            utf8Writer.writeObject("world!")
            structVec.endStruct()

            assertEquals(
                listOf(
                    mapOf("i32" to 4),
                    mapOf("i32" to 8, "utf8" to "Hello"),
                    mapOf("i32" to 12, "utf8" to "world!")
                ),
                structVec.asList
            )
        }

    }

    @Test
    fun roundTripsThroughFile() {
        val m1 = mapOf("i32" to 4, "utf8" to "Hello")
        val m2 = mapOf("i32" to 8)
        val m3 = mapOf("i32" to 12, "utf8" to "world!")

        val buf = ByteArrayOutputStream()

        val children = linkedMapOf(
            "i32" to IntVector(allocator, "i32", false),
            "utf8" to Utf8Vector(allocator, "utf8", true)
        )

        StructVector(allocator, "struct", false, children).use { structVec ->
            val rel = Relation(listOf(structVec))

            rel.startUnload(Channels.newChannel(buf))
                .use { unloader ->
                    structVec.writeObject(m1)
                    rel.endRow()
                    structVec.writeObject(m2)
                    rel.endRow()

                    unloader.writeBatch()
                    rel.clear()

                    structVec.writeObject(m3)
                    rel.endRow()

                    unloader.writeBatch()

                    unloader.endFile()
                }
        }

        loader(allocator, ByteBufferChannel(ByteBuffer.wrap(buf.toByteArray()))).use { loader ->
            Relation(allocator, loader.schema).use { rel ->
                val structVec = rel["struct"]!!

                assertEquals(2, loader.batchCount)

                loader.loadBatch(0, rel)

                assertEquals(2, rel.rowCount)
                assertEquals(m1, structVec.getObject(0))
                assertEquals(m2, structVec.getObject(1))

                loader.loadBatch(1, rel)

                assertEquals(1, rel.rowCount)
                assertEquals(m3, structVec.getObject(0))

                loader.loadBatch(0, rel)

                assertEquals(2, rel.rowCount)
                assertEquals(m1, structVec.getObject(0))
                assertEquals(m2, structVec.getObject(1))
            }
        }
    }

}