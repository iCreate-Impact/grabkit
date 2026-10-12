package com.grabkit.extractor

import com.grabkit.model.MediaInfo

/** Turns a (normalized) post URL into metadata + downloadable sources. Throws [com.grabkit.util.AppError]. */
interface Extractor {
    suspend fun extract(url: String): MediaInfo
}
