package tv.cleanyour.core.flows

import tv.cleanyour.core.model.BlobRef
import tv.cleanyour.core.model.TvId

// 0.4.0: the person's own photo for one TV. The phone makes it (crop to 16:9, 1920 x 1080, a JPEG of
// at most MAX_BYTES, re-encoded so no metadata such as the place or the camera is left) and keeps it
// in the app's private files at tvs/<id>/picture.jpg: not backed up, gone with "Forget this TV",
// never in a TV report or a restore point. Each push puts it into the BlobStore (a cache the system
// may clear) and sends it as the launcher's wallpaper.jpg, the same push a look's own picture takes.
internal object OwnPicture {
    const val MAX_BYTES = 280_000
    const val DEFAULT_DIM = 30

    fun path(id: TvId) = "tvs/${id.value}/picture.jpg"

    // Keeps the photo for the TV; refused unless it is a JPEG within the size the push allows.
    suspend fun save(d: ManageDeps, id: TvId, jpeg: ByteArray): BlobRef {
        require(jpeg.size in 4..MAX_BYTES && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "not a JPEG of at most $MAX_BYTES bytes" }
        d.documents.write(path(id), jpeg)
        return d.blobs.putBytes(jpeg)
    }

    // The kept photo as a file the push can send, or null when this TV has none.
    suspend fun ref(d: ManageDeps, id: TvId): BlobRef? {
        val bytes = d.documents.read(path(id)) { it } ?: return null
        return d.blobs.get(sha256Of(bytes)) ?: d.blobs.putBytes(bytes)
    }

    // A HomeChoice.picture to what the push sends: the own photo with its dim, or a gallery picture.
    suspend fun lookPicture(d: ManageDeps, id: TvId, picture: String?, dim: Int?): LookPicture? =
        if (picture == LookPicture.MINE_ID) ref(d, id)?.let { LookPicture.Of(it, (dim ?: DEFAULT_DIM).coerceIn(0, 90)) }
        else LookPicture.of(picture, d.pictures)
}
