package com.stash.core.data.weblink.mirror

/** A [MirrorStore] in memory (tests). */
class InMemoryMirrorStore : MirrorStore {
    var record: MirrorRecord? = null
    var saves = 0

    override suspend fun load(): MirrorRecord? = record?.let {
        // Through JSON, as the file store does, so a test sees exactly what survives a save.
        FileMirrorStore.json.decodeFromString(MirrorRecord.serializer(), FileMirrorStore.json.encodeToString(MirrorRecord.serializer(), it))
    }

    override suspend fun save(r: MirrorRecord) {
        record = FileMirrorStore.json.decodeFromString(MirrorRecord.serializer(), FileMirrorStore.json.encodeToString(MirrorRecord.serializer(), r))
        map = r.spaceId to r.map
        saves++
    }

    var map: Pair<String, Map<String, MappedPl>>? = null

    override suspend fun clear() {
        record = null
        map = null
    }

    override suspend fun savedMap(spaceId: String): Map<String, MappedPl> = map?.takeIf { it.first == spaceId }?.second.orEmpty()
}
