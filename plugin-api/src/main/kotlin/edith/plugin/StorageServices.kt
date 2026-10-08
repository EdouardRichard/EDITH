package edith.plugin

interface StorageHandle {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun delete(key: String)
    fun clear()
}
