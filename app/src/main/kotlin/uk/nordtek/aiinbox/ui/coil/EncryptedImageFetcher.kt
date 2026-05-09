package uk.nordtek.aiinbox.ui.coil

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import uk.nordtek.aiinbox.data.db.Attachment
import uk.nordtek.aiinbox.data.storage.EncryptedImageStore
import okio.buffer
import okio.source

/** Coil で `Attachment` を直接 `AsyncImage(model = attachment)` できるようにする Fetcher。 */
class EncryptedImageFetcher(
    private val attachment: Attachment,
    private val store: EncryptedImageStore,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val stream = store.read(attachment.encryptedFilename)
        return SourceResult(
            source = ImageSource(stream.source().buffer(), options.context),
            mimeType = attachment.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val store: EncryptedImageStore,
    ) : Fetcher.Factory<Attachment> {
        override fun create(data: Attachment, options: Options, imageLoader: ImageLoader): Fetcher =
            EncryptedImageFetcher(data, store, options)
    }
}
